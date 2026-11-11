import {spawnSync,spawn} from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {randomUUID,randomBytes,generateKeyPairSync,sign} from 'node:crypto';
import grpc from '@grpc/grpc-js';
import loader from '@grpc/proto-loader';
const id='tiket-'+randomUUID();
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'tiket-kind-'));
const kube=path.join(dir,'config');
const env={...process.env,KUBECONFIG:kube,
 HELM_CONFIG_HOME:path.join(dir,'helm-config'),HELM_CACHE_HOME:path.join(dir,'helm-cache'),
 HELM_DATA_HOME:path.join(dir,'helm-data'),HELM_REGISTRY_CONFIG:path.join(dir,'helm-config','registry.json')};
const image=process.env.TIKET_TEST_IMAGE||'tiket:kind';
const fixtureUID=process.getuid?String(process.getuid()):'0';
const processes=[];
const dockerContainers=[];
const dockerVolumes=[];
let owned=false,failed=true,cleanupStarted=false;
const out=path.resolve('build/kind-'+id);
fs.mkdirSync(out,{recursive:true});
fs.writeFileSync(path.join(out,'cluster-name'),id);
function run(cmd,args,opts={}) {
 console.log(cmd,args.filter(a=>!a.includes('password')).join(' '));
 const r=spawnSync(cmd,args,{env,encoding:'utf8',timeout:600000,...opts});
 if(r.status!==0) throw Error(cmd+': '+(r.stderr||r.error||r.stdout));
 return r.stdout;
}
const k=(...args)=>run('kubectl',['--kubeconfig',kube,...args]);
const helm=(...args)=>run('helm',[...args,'--kubeconfig',kube]);
function apply(obj){run('kubectl',['--kubeconfig',kube,'apply','-f','-'],{input:JSON.stringify(obj)});}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const http=(url,options={})=>fetch(url,{...options,signal:AbortSignal.timeout(10000)});
async function until(fn,seconds=120){let e;const deadline=Date.now()+seconds*1000;while(Date.now()<deadline){try{return await fn();}catch(x){e=x;await sleep(1000);}}throw e;}
function forward(resource,port,target){
 const p=spawn('kubectl',['--kubeconfig',kube,'port-forward',resource,port+':'+target],{env,stdio:'ignore'});
 processes.push(p);return p;
}
function cleanup(){
 if(cleanupStarted)return;cleanupStarted=true;
 for(const p of processes)p.kill();
 for(const name of dockerContainers)spawnSync('docker',['rm','-f',name],{env,stdio:'ignore',timeout:30000});
 for(const name of dockerVolumes){
  const r=spawnSync('docker',['volume','rm',name],{env,stdio:'inherit',timeout:30000});
  if(r.status!==0)process.exitCode=1;
 }
 if(owned){
  let r;
  for(let attempt=0;attempt<3;attempt++){
   r=spawnSync('kind',['delete','cluster','--name',id],{env,stdio:'inherit',timeout:180000});
   if(r.status===0)break;
  }
  if(r.status!==0)process.exitCode=1;
 }
 fs.rmSync(dir,{recursive:true,force:true,maxRetries:3,retryDelay:100});
}
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>{cleanup();process.exit(1);});
const selector={app:'postgres'};
const password=randomBytes(24).toString('hex'),runtime=randomBytes(24).toString('hex');
const day=randomUUID(),player=randomUUID();
const issuer='https://issuer.default.svc:8443';
const pair=generateKeyPairSync('rsa',{modulusLength:2048});
const signing=pair.privateKey.export({type:'pkcs8',format:'pem'});
fs.writeFileSync(path.join(dir,'signing.pem'),signing,{mode:0o600});
function token(extra={}){
 const head=Buffer.from(JSON.stringify({alg:'RS256',kid:'kind'})).toString('base64url');
 const body=Buffer.from(JSON.stringify({sub:'operator',iss:issuer,aud:'tiket',exp:Math.floor(Date.now()/1000)+600,
 tiket_roles:['EVENT_ADMIN','CASHIER'],tiket_events:[day],tiket_players:[player],tiket_pos:['1'],tiket_cashier:'kind-cashier',...extra})).toString('base64url');
 const data=head+'.'+body;
 return data+'.'+sign('RSA-SHA256',Buffer.from(data),pair.privateKey).toString('base64url');
}
const definitions=grpc.loadPackageDefinition(loader.loadSync([
 'pl/umamusume/tiket/api/v1/system.proto','pl/umamusume/tiket/api/v1/administration.proto','pl/umamusume/tiket/api/v1/betting.proto','pl/umamusume/tiket/api/v1/account.proto'
],{includeDirs:['tiket-contracts/src/main/proto'],keepCase:true,longs:String,defaults:true}));
const api=definitions.pl.umamusume.tiket.api.v1;
let grpcTLS=false,eventCreated=false;
function client(port){
 const credentials=grpcTLS?grpc.credentials.createSsl(fs.readFileSync(path.join(dir,'backend.crt'))):grpc.credentials.createInsecure();
 const channelOptions=process.argv.includes('--ingress')&&port===19080?{'grpc.default_authority':'tiket.kind.test'}:{};
 const make=type=>new api[type]('127.0.0.1:'+port,credentials,channelOptions);
 return {
 system:make('SystemService'),day:make('RaceDayService'),race:make('RaceService'),
 enrollment:make('EnrollmentService'),betting:make('BettingService'),printing:make('PrintingService'),account:make('AccountService')
};}
function call(c,method,data,jwt){
 const meta=new grpc.Metadata();if(jwt)meta.set('authorization','Bearer '+jwt);
 return new Promise((resolve,reject)=>c[method](data,meta,{deadline:Date.now()+10000},(e,r)=>e?reject(e):resolve(r)));
}
async function denied(c,method,data,jwt,code){try{await call(c,method,data,jwt);}catch(e){if(e.code===code)return;throw e;}throw Error('expected denial');}
async function traffic(port){
 const c=client(port);
 try{
 await until(()=>call(c.system,'Ping',{}));
 await denied(c.day,'GetRaceDay',{race_day_id:day},undefined,grpc.status.UNAUTHENTICATED);
 for(const claims of [{aud:'wrong'},{iss:'https://wrong.invalid'},{exp:1}])
  await denied(c.day,'GetRaceDay',{race_day_id:day},token(claims),grpc.status.UNAUTHENTICATED);
 await denied(c.day,'GetRaceDay',{race_day_id:day},token({tiket_roles:[]}),grpc.status.PERMISSION_DENIED);
 await denied(c.day,'GetRaceDay',{race_day_id:day},token({tiket_events:[]}),grpc.status.PERMISSION_DENIED);
 const parts=token().split('.');parts[2]=(parts[2][0]==='A'?'B':'A')+parts[2].slice(1);
 await denied(c.day,'GetRaceDay',{race_day_id:day},parts.join('.'),grpc.status.UNAUTHENTICATED);
 if(eventCreated){
  const info=await call(c.day,'GetRaceDay',{race_day_id:day},token());
  if(info.definition.race_day_id!==day)throw Error('authorized event read');
 }
 }finally{Object.values(c).forEach(x=>x.close());}
}
const peerForwards=[];
async function financialPeers(){
 for(const p of peerForwards)p.kill();peerForwards.length=0;
 await sleep(500);
 const pods=JSON.parse(k('get','pods','-l','app.kubernetes.io/instance=tiket','-o','json')).items
  .filter(p=>!p.metadata.deletionTimestamp&&p.status.conditions?.some(c=>c.type==='Ready'&&c.status==='True')).slice(0,2);
 if(pods.length!==2)throw Error('Two ready financial replicas required');
 console.log('Financial peers: '+pods.map(p=>p.metadata.name).join(','));
 const ports=[19094,19095];
 pods.forEach((p,i)=>peerForwards.push(forward('pod/'+p.metadata.name,ports[i],9090)));
 const clients=ports.map(client);
 for(const c of clients)await until(()=>call(c.system,'Ping',{}));
 return clients;
}
try {
 run('docker',['build','-t','tiket-kind-issuer:local','scripts/kind']);
 run('docker',['run','--rm','--user',fixtureUID,'--entrypoint','openssl','-v',dir+':/work','tiket-kind-issuer:local',
 'req','-x509','-newkey','rsa:2048','-nodes','-days','1','-keyout','/work/tls.key','-out','/work/tls.crt',
 '-subj','/CN=issuer.default.svc','-addext','subjectAltName=DNS:issuer.default.svc']);
 // Refuse existing names; mark ownership before creation so partial creations are removed.
 if(run('kind',['get','clusters']).trim().split(/\r?\n/).includes(id))throw Error('Refusing to reuse an existing kind cluster');
 owned=true;
 if(process.env.GITHUB_OUTPUT) fs.appendFileSync(process.env.GITHUB_OUTPUT,'cluster='+id+'\n');
 const kindArgs=['create','cluster','--name',id,'--image',process.env.KIND_NODE_IMAGE||'kindest/node:v1.35.0','--kubeconfig',kube,'--wait','180s'];
 if(process.argv.includes('--allow-cgroup-v1')){
  console.log('Explicit test-only cgroup v1 compatibility enabled; upgrade Docker engine to cgroup v2.');
  const config=path.join(dir,'kind.yaml');
  fs.writeFileSync(config,'kind: Cluster\napiVersion: kind.x-k8s.io/v1alpha4\nkubeadmConfigPatches:\n- |\n  kind: KubeletConfiguration\n  apiVersion: kubelet.config.k8s.io/v1beta1\n  failCgroupV1: false\n');
  kindArgs.push('--config',config);
 }
 run('kind',kindArgs);
 run('docker',['build','-t',image,'.']);
 run('kind',['load','docker-image','--name',id,image,'tiket-kind-issuer:local']);
 apply({apiVersion:'v1',kind:'Secret',metadata:{name:'postgres'},stringData:{password}});
 apply({apiVersion:'v1',kind:'Secret',metadata:{name:'tiket-db-migration'},stringData:{username:'migrator',password}});
 apply({apiVersion:'v1',kind:'Secret',metadata:{name:'tiket-db-runtime'},stringData:{username:'runtime',password:runtime}});
 apply({apiVersion:'v1',kind:'PersistentVolumeClaim',metadata:{name:'postgres'},spec:{accessModes:['ReadWriteOnce'],resources:{requests:{storage:'1Gi'}}}});
 apply({apiVersion:'apps/v1',kind:'Deployment',metadata:{name:'postgres'},spec:{replicas:1,strategy:{type:'Recreate'},selector:{matchLabels:selector},template:{metadata:{labels:selector},spec:{containers:[{
 name:'postgres',image:'postgres:18.1',env:[{name:'POSTGRES_PASSWORD',valueFrom:{secretKeyRef:{name:'postgres',key:'password'}}}],
 ports:[{containerPort:5432}],volumeMounts:[{name:'data',mountPath:'/var/lib/postgresql'}],
 readinessProbe:{exec:{command:['pg_isready','-h','127.0.0.1','-U','postgres']},periodSeconds:2}
 }],volumes:[{name:'data',persistentVolumeClaim:{claimName:'postgres'}}]}}}});
 apply({apiVersion:'v1',kind:'Service',metadata:{name:'postgres'},spec:{selector,ports:[{port:5432}]}});
 k('rollout','status','deployment/postgres','--timeout=180s');
 function psql(text){return run('kubectl',['--kubeconfig',kube,'exec','-i','deployment/postgres','--','psql','-U','postgres','-v','ON_ERROR_STOP=1'],{input:text});}
 psql("CREATE ROLE migrator LOGIN PASSWORD '"+password+"'; CREATE ROLE runtime LOGIN PASSWORD '"+runtime+"'; CREATE DATABASE tiket OWNER migrator;");
 psql("\\connect tiket\nGRANT USAGE ON SCHEMA public TO runtime; ALTER DEFAULT PRIVILEGES FOR ROLE migrator IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO runtime; ALTER DEFAULT PRIVILEGES FOR ROLE migrator IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO runtime;");
 const secret={};for(const n of ['signing.pem','tls.key','tls.crt'])secret[n]=fs.readFileSync(path.join(dir,n)).toString('base64');
 apply({apiVersion:'v1',kind:'Secret',metadata:{name:'issuer'},data:secret});
 apply({apiVersion:'apps/v1',kind:'Deployment',metadata:{name:'issuer'},spec:{selector:{matchLabels:{app:'issuer'}},template:{metadata:{labels:{app:'issuer'}},spec:{containers:[{name:'issuer',image:'tiket-kind-issuer:local',imagePullPolicy:'Never',volumeMounts:[{name:'keys',mountPath:'/keys',readOnly:true}]}],volumes:[{name:'keys',secret:{secretName:'issuer'}}]}}}});
 apply({apiVersion:'v1',kind:'Service',metadata:{name:'issuer'},spec:{selector:{app:'issuer'},ports:[{port:8443}]}});
 k('rollout','status','deployment/issuer','--timeout=120s');
 run('docker',['run','--rm','--user',fixtureUID,'--entrypoint','keytool','-v',dir+':/work',image,
 '-importcert','-noprompt','-alias','issuer','-file','/work/tls.crt','-keystore','/work/trust.p12','-storepass','changeit']);
 apply({apiVersion:'v1',kind:'Secret',metadata:{name:'issuer-trust'},data:{'trust.p12':fs.readFileSync(path.join(dir,'trust.p12')).toString('base64')}});
 const values={replicaCount:2,image:{repository:image.split(':')[0],tag:image.split(':')[1],pullPolicy:'Never'},
 database:{url:'jdbc:postgresql://postgres:5432/tiket?connectTimeout=3&socketTimeout=5'},
 jwt:{issuer,jwksUri:issuer+'/jwks',audience:'tiket'},
 extraEnv:[{name:'TIKET_PRINTING_ENABLED',value:'false'},{name:'JAVA_TOOL_OPTIONS',value:'-Djavax.net.ssl.trustStore=/run/issuer/trust.p12 -Djavax.net.ssl.trustStorePassword=changeit -Djava.io.tmpdir=/tmp'}],
 extraVolumes:[{name:'issuer-trust',secret:{secretName:'issuer-trust'}}],extraVolumeMounts:[{name:'issuer-trust',mountPath:'/run/issuer',readOnly:true}]};
 const valuesFile=path.join(dir,'values.json');
 fs.writeFileSync(valuesFile,JSON.stringify(values));
 let rejected=false;
 try{
  helm('install','blocked','charts/tiket','-f',valuesFile,'--set','database.migrationSecret=tiket-db-runtime','--wait','--timeout','90s');
 }catch{rejected=true;}
 if(!rejected||k('get','deployment','blocked','--ignore-not-found','-o','name').trim())throw Error('failed migration did not gate rollout');
 helm('uninstall','blocked','--wait','--timeout','90s');
 helm('upgrade','--install','tiket','charts/tiket','-f',valuesFile,'--wait','--timeout','5m');
 psql("\\connect tiket\nREVOKE UPDATE,DELETE,TRUNCATE ON operational_audit,command_actor FROM runtime;");
 forward('service/tiket',19090,9090);forward('service/tiket-management',19091,9091);
 async function health(name,status=200){const r=await http('http://127.0.0.1:19091/actuator/health/'+name);if(r.status!==status)throw Error(name+': '+r.status);}
 await until(()=>health('readiness'));await health('liveness');
 const metrics=await http('http://127.0.0.1:19091/actuator/prometheus');if(!metrics.ok||!(await metrics.text()).includes('jvm_'))throw Error('metrics');
 await traffic(19090);
 const c=client(19090);
 const request={idempotency_key:randomUUID(),definition:{race_day_id:day,event_date:'2026-11-11',display_number:1,currency:'PLN',time_zone:'Europe/Warsaw',rules_version:'v1',maximum_coupon_legs:10000,maximum_open_races:1,no_winner_policy:1,races:[{
 race_id:randomUUID(),number:1,racecourse_name:'kind',title:'kind',
 runners:[1,2,3].map(i=>({horse_id:randomUUID(),program_number:String(i),display_name:'horse '+i})),
 pools:[1,2,3,4].map(type=>({type,unit_stake:'10',take_basis_points:1000}))
 }]}};
 const first=await call(c.day,'CreateRaceDay',request,token());
 eventCreated=true;
 const replay=await call(c.day,'CreateRaceDay',request,token());
 if(JSON.stringify(first)!==JSON.stringify(replay))throw Error('replay mismatch');
 await denied(c.day,'CreateRaceDay',request,token({sub:'other'}),grpc.status.PERMISSION_DENIED);
 await call(c.enrollment,'EnrollPlayer',{race_day_id:day,player_id:player,opening_paid:'1000',expected_day_version:first.version,idempotency_key:randomUUID()},token());
 const published=await call(c.day,'PublishRaceDay',{race_day_id:day,expected_version:first.version,idempotency_key:randomUUID()},token());
 const started=await call(c.day,'StartRaceDay',{race_day_id:day,expected_version:published.version,idempotency_key:randomUUID()},token());
 const race=request.definition.races[0];
 const opened=await call(c.race,'OpenBetting',{race_day_id:day,race_id:race.race_id,expected_day_version:started.version,expected_race_version:'0',idempotency_key:randomUUID()},token());
 const quoteRequest={race_day_id:day,race_id:race.race_id,bet_type:1,selection:{positional:{positions:[{horse_id:race.runners[0].horse_id}]}},units_per_combination:'1'};
 const quote=await call(c.betting,'QuoteCoupon',quoteRequest,token());
 const purchase={...quoteRequest,player_id:player,coupon_id:randomUUID(),quote_confirmation:quote.confirmation,expected_day_version:quote.race_day_version,expected_race_version:quote.race_version,point_of_sale_number:1,cashier_identifier:'kind-cashier',idempotency_key:randomUUID()};
 const purchasePeers=await financialPeers();
 const bought=await Promise.all(purchasePeers.map(p=>call(p.betting,'PurchaseCoupon',purchase,token())));
 purchasePeers.forEach(p=>Object.values(p).forEach(x=>x.close()));
 if(JSON.stringify(bought[0])!==JSON.stringify(bought[1])||bought[0].balance!=='990')throw Error('concurrent purchase replay');
 await denied(c.betting,'PurchaseCoupon',purchase,token({tiket_pos:['2']}),grpc.status.PERMISSION_DENIED);
 Object.values(c).forEach(x=>x.close());
 // Run the replacement under an OpenShift-style arbitrary numeric UID.
 k('patch','deployment','tiket','--type=merge','-p',JSON.stringify({spec:{template:{spec:{securityContext:{runAsUser:1001234567}}}}}));
 k('rollout','restart','deployment/tiket');k('rollout','status','deployment/tiket','--timeout=300s');
 // Port forwards may terminate during replacement; open new forwards.
 processes.forEach(p=>p.kill());await sleep(1000);processes.length=0;
 forward('service/tiket',19090,9090);forward('service/tiket-management',19091,9091);
 await until(()=>health('readiness'));
 const restarted=client(19090);
 const persisted=await call(restarted.day,'CreateRaceDay',request,token());
 if(JSON.stringify(first)!==JSON.stringify(persisted))throw Error('durable replay mismatch');
 const restored=await call(restarted.betting,'PurchaseCoupon',purchase,token());
 if(JSON.stringify(restored)!==JSON.stringify(bought[0]))throw Error('purchase replay after response loss and restart');
 const pending=await call(restarted.printing,'GetPrintStatus',{print_job_id:bought[0].print_job_id},token());
 if(pending.status!==1)throw Error('expected pending print job');
 Object.values(restarted).forEach(x=>x.close());
 values.extraEnv[0].value='true';
 fs.writeFileSync(valuesFile,JSON.stringify(values));
 helm('upgrade','tiket','charts/tiket','-f',valuesFile,'--wait','--timeout','5m');
 processes.forEach(p=>p.kill());await sleep(1000);processes.length=0;
 forward('service/tiket',19090,9090);forward('service/tiket-management',19091,9091);
 const recovered=client(19090);
 await until(async()=>{const s=await call(recovered.printing,'GetPrintStatus',{print_job_id:bought[0].print_job_id},token());if(s.status!==4)throw Error('print not dispatched');});
 await call(recovered.race,'VoidRace',{race_day_id:day,race_id:race.race_id,expected_day_version:started.version,expected_race_version:opened.version,idempotency_key:randomUUID()},token());
 const finalizing=await call(recovered.day,'FinalizeRaceDay',{race_day_id:day,expected_version:started.version,idempotency_key:randomUUID()},token());
 const completed=await call(recovered.day,'CompleteRaceDay',{race_day_id:day,expected_version:finalizing.version,idempotency_key:randomUUID()},token());
 const account=await call(recovered.account,'GetAccount',{race_day_id:day,player_id:player},token());
 const cashout={race_day_id:day,player_id:player,expected_day_version:completed.version,expected_account_version:account.account_version,idempotency_key:randomUUID()};
 const cashoutPeers=await financialPeers();
 const paid=await Promise.all(cashoutPeers.map(p=>call(p.account,'CashOut',cashout,token())));
 cashoutPeers.forEach(p=>Object.values(p).forEach(x=>x.close()));
 if(JSON.stringify(paid[0])!==JSON.stringify(paid[1])||paid[0].withdrawn!=='1000')throw Error('concurrent cashout replay');
 Object.values(recovered).forEach(x=>x.close());
 k('scale','deployment/postgres','--replicas=0');
 await until(()=>health('readiness',503),90);await health('liveness');
 k('scale','deployment/postgres','--replicas=1');
 // The test PVC retains state across outage; deleting the kind cluster deletes its storage.
 k('rollout','status','deployment/postgres','--timeout=180s');
 await until(()=>health('readiness'),120);
 if(process.argv.includes('--ingress')){
  helm('install','edge','oci://ghcr.io/traefik/helm/traefik','--version','37.4.0','--namespace','edge','--create-namespace','--wait','--timeout','5m');
  values.exposure={mode:'ingress',ingress:{className:'edge-traefik',hostname:'tiket.kind.test'}};
  values.service={annotations:{'traefik.ingress.kubernetes.io/service.serversscheme':'h2c'}};
  fs.writeFileSync(valuesFile,JSON.stringify(values));
  helm('upgrade','tiket','charts/tiket','-f',valuesFile,'--wait','--timeout','5m');
  const proxy=spawn('kubectl',['--kubeconfig',kube,'-n','edge','port-forward','svc/edge-traefik','19080:80'],{env,stdio:'ignore'});processes.push(proxy);
  await traffic(19080);
 }else if(!process.argv.includes('--no-gateway')){
  helm('install','eg','oci://docker.io/envoyproxy/gateway-helm','--version','v1.8.6','-n','envoy-gateway-system','--create-namespace','--wait','--timeout','5m');
  apply({apiVersion:'gateway.networking.k8s.io/v1',kind:'GatewayClass',metadata:{name:'eg'},spec:{controllerName:'gateway.envoyproxy.io/gatewayclass-controller'}});
  apply({apiVersion:'gateway.networking.k8s.io/v1',kind:'Gateway',metadata:{name:'test'},spec:{gatewayClassName:'eg',listeners:[{name:'grpc',protocol:'HTTP',port:80}]}});
  values.exposure={mode:'gateway',gateway:{parentRefs:[{name:'test',sectionName:'grpc'}]}};
  fs.writeFileSync(valuesFile,JSON.stringify(values));
  helm('upgrade','tiket','charts/tiket','-f',valuesFile,'--wait','--timeout','5m');
  await until(()=>{const r=JSON.parse(k('get','grpcroutes','tiket','-o','json'));if(!r.status?.parents?.some(p=>p.conditions?.some(c=>c.type==='Accepted'&&c.status==='True')))throw Error('GRPCRoute not accepted');});
  const svc=await until(()=>{const s=JSON.parse(k('get','svc','-n','envoy-gateway-system','-l','gateway.envoyproxy.io/owning-gateway-name=test','-o','json'));if(!s.items.length)throw Error('gateway service');return s.items[0].metadata.name;});
  k('wait','pod','-n','envoy-gateway-system','-l','gateway.envoyproxy.io/owning-gateway-name=test','--for=condition=Ready','--timeout=180s');
  const gatewayLog=fs.openSync(path.join(out,'gateway-port-forward.log'),'a');
  const p=spawn('kubectl',['--kubeconfig',kube,'-n','envoy-gateway-system','port-forward','svc/'+svc,'19080:80'],{env,stdio:['ignore',gatewayLog,gatewayLog]});processes.push(p);
  await traffic(19080);
  const r=await http('http://127.0.0.1:19080/actuator/health/readiness');if(r.ok)throw Error('management publicly routed');
 }
 run('docker',['run','--rm','--user',fixtureUID,'--entrypoint','openssl','-v',dir+':/work','tiket-kind-issuer:local',
 'req','-x509','-newkey','rsa:2048','-nodes','-days','1','-keyout','/work/backend.key','-out','/work/backend.crt',
 '-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost,IP:127.0.0.1,DNS:tiket.default.svc']);
 apply({apiVersion:'v1',kind:'Secret',metadata:{name:'backend-tls'},data:{'tls.crt':fs.readFileSync(path.join(dir,'backend.crt')).toString('base64'),'tls.key':fs.readFileSync(path.join(dir,'backend.key')).toString('base64')}});
 values.transport='direct-tls';values.tls={secret:'backend-tls'};values.exposure={mode:'none'};
 fs.writeFileSync(valuesFile,JSON.stringify(values));
 helm('upgrade','tiket','charts/tiket','-f',valuesFile,'--wait','--timeout','5m');
 processes.forEach(p=>p.kill());await sleep(1000);processes.length=0;
 forward('service/tiket',19090,9090);grpcTLS=true;await traffic(19090);
 // Exercise the same image directly with Docker against the temporary kind fixtures.
 for(const [name,label,port,nodePort] of [['postgres-docker','postgres',5432,30432],['issuer-docker','issuer',8443,30443]])
  apply({apiVersion:'v1',kind:'Service',metadata:{name},spec:{type:'NodePort',selector:{app:label},ports:[{port,targetPort:port,nodePort}]}});
 const nodeIP=run('docker',['inspect',id+'-control-plane','--format','{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}']).trim();
 const dockerMaterial=path.join(dir,'docker-material');
 fs.mkdirSync(dockerMaterial,{mode:0o755});
 for(const file of ['trust.p12','backend.crt','backend.key']){
  fs.copyFileSync(path.join(dir,file),path.join(dockerMaterial,file));
  fs.chmodSync(path.join(dockerMaterial,file),0o444);
 }
 const materialVolume=id+'-certs';
 run('docker',['volume','create','--label','tiket.test-invocation='+id,materialVolume]);dockerVolumes.push(materialVolume);
 run('docker',['run','--rm','--user','0','--network','none','--entrypoint','bash',
  '-v',dockerMaterial+':/source:ro','-v',materialVolume+':/target','tiket-kind-issuer:local',
  '-c','cp /source/* /target/ && chmod 0444 /target/* && chmod 0755 /target']);
 for(const tls of [false,true]){
  const name=id+'-docker-'+(tls?'tls':'h2c');dockerContainers.push(name);
  const vars={
   SPRING_PROFILES_ACTIVE:'production',TIKET_TRANSPORT:tls?'direct-tls':'gateway-h2c',
   SPRING_FLYWAY_ENABLED:'false',TIKET_PRINTING_ENABLED:'false',
   GRPC_ADDRESS:'0.0.0.0',MANAGEMENT_ADDRESS:'0.0.0.0',
   DB_URL:'jdbc:postgresql://'+nodeIP+':30432/tiket?connectTimeout=3&socketTimeout=5',
   DB_USERNAME:'runtime',DB_PASSWORD:runtime,JWT_ISSUER:issuer,
   JWT_JWKS_URI:'https://issuer.default.svc:30443/jwks',JWT_AUDIENCE:'tiket',
   GRPC_TLS_ENABLED:String(tls),
   JAVA_TOOL_OPTIONS:'-Djavax.net.ssl.trustStore=/run/test/trust.p12 -Djavax.net.ssl.trustStorePassword=changeit -Djava.io.tmpdir=/tmp -Duser.home=/tmp'
  };
  if(tls)Object.assign(vars,{
   SPRING_GRPC_SERVER_SSL_BUNDLE:'grpc',
   SPRING_SSL_BUNDLE_PEM_GRPC_KEYSTORE_CERTIFICATE:'file:/run/test/backend.crt',
   SPRING_SSL_BUNDLE_PEM_GRPC_KEYSTORE_PRIVATE_KEY:'file:/run/test/backend.key'
  });
  const envFile=path.join(dir,'docker.env');fs.writeFileSync(envFile,Object.entries(vars).map(([k,v])=>k+'='+v).join('\n'),{mode:0o600});
  run('docker',['run','-d','--name',name,'--network','kind','--add-host','issuer.default.svc:'+nodeIP,
   '--read-only','--user','1001234567:0','--memory','1g','--cap-drop','ALL','--security-opt','no-new-privileges',
   '--tmpfs','/tmp:rw,nosuid,nodev,size=128m','-v',materialVolume+':/run/test:ro','--env-file',envFile,
   '-p','127.0.0.1:19093:9090','-p','127.0.0.1:19092:9091',image]);
  await until(async()=>{const r=await http('http://127.0.0.1:19092/actuator/health/readiness');if(!r.ok)throw Error('Docker readiness '+r.status);},180);
  grpcTLS=tls;await traffic(19093);
  run('docker',['stop','--time','90',name]);
  const exit=Number(run('docker',['inspect',name,'--format','{{.State.ExitCode}}']).trim());
  const logs=run('docker',['logs',name]);
  fs.writeFileSync(path.join(out,'docker-'+(tls?'tls':'h2c')+'.log'),logs);
  if(![0,143].includes(exit)||!logs.includes('Completed gRPC server shutdown'))throw Error('Docker SIGTERM shutdown failed');
  run('docker',['rm',name]);
 }
 failed=false;console.log('kind deployment checks passed: '+id);
} catch(e) {console.error(e);process.exitCode=1;}
finally {
 if(failed&&owned){
  for(const name of dockerContainers){
   try{fs.writeFileSync(path.join(out,name+'.log'),run('docker',['logs',name]));}catch(e){console.error(e.message);}
  }
  for(const args of [['get','all','-A','-o','wide'],['get','events','-A'],['get','ingress,ingressclass','-A','-o','yaml'],['logs','-n','edge','deployment/edge-traefik','--tail=100'],['logs','-n','envoy-gateway-system','deployment/envoy-gateway','--tail=100'],['logs','-l','app.kubernetes.io/name=tiket','--all-containers','--prefix','--tail=500'],['describe','pods']]){
   try{fs.writeFileSync(path.join(out,args[0]+'-'+randomUUID().slice(0,4)+'.log'),k(...args));}catch(e){console.error(e.message);}
  }
 }
 cleanup();
}
