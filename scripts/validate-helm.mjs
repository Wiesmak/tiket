import {spawnSync} from 'node:child_process';
import assert from 'node:assert/strict';
import YAML from 'yaml';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
function render(values={}) {
 const args=['template','tiket','charts/tiket'];
 for(const [key,value] of Object.entries(values)) args.push('--set',key+'='+value);
 const r=spawnSync('helm',args,{encoding:'utf8'});
 assert.equal(r.status,0,r.stderr);
 const docs=YAML.parseAllDocuments(r.stdout).map(d=>d.toJSON()).filter(Boolean);
 assert(!docs.some(d=>['Route','ImageStream','BuildConfig','Cluster','Postgresql','StatefulSet'].includes(d.kind)));
 for(const d of docs.filter(d=>['Ingress','GRPCRoute'].includes(d.kind)))assert(!JSON.stringify(d.spec).includes('management'));
 const dep=docs.find(d=>d.kind==='Deployment');
 assert.equal(dep.spec.template.spec.automountServiceAccountToken,false);
 assert.equal(dep.spec.template.spec.securityContext.runAsNonRoot,true);
 assert.equal(dep.spec.template.spec.securityContext.runAsUser,undefined);
 assert.equal(dep.spec.template.spec.containers[0].securityContext.readOnlyRootFilesystem,true);
 return docs;
}
const lint=spawnSync('helm',['lint','charts/tiket','--strict'],{stdio:'inherit'});assert.equal(lint.status,0);
render();render({'exposure.mode':'none','migration.enabled':false});
const gateway=render({'exposure.mode':'gateway','exposure.gateway.parentRefs[0].name':'test'});
assert.equal(gateway.filter(d=>d.kind==='GRPCRoute').length,1);assert(!gateway.some(d=>d.kind==='Ingress'));
const ingress=render({'exposure.mode':'ingress','exposure.ingress.hostname':'tiket.example','exposure.ingress.annotations.nginx\\.ingress\\.kubernetes\\.io/backend-protocol':'GRPC'});
assert.equal(ingress.filter(d=>d.kind==='Ingress').length,1);assert(!ingress.some(d=>d.kind==='GRPCRoute'));
render({'transport':'direct-tls','tls.secret':'server','tls.clientAuth':'require','database.tlsSecret':'db-ca'});
render({'replicaCount':2,'pdb.enabled':true,'hpa.enabled':true,'serviceMonitor.enabled':true,'networkPolicy.enabled':true});
render({'image.digest':'sha256:'+'a'.repeat(64),'migration.mode':'explicit','migration.revision':'2'});
render({'image.tag':'0.0.0-dev.123.abcdef0'});render({'image.tag':'1.2.3'});
const invalid=spawnSync('helm',['template','tiket','charts/tiket','--set','exposure.mode=both'],{encoding:'utf8'});assert.notEqual(invalid.status,0);
const packages=fs.mkdtempSync(path.join(os.tmpdir(),'tiket-helm-'));
try{
 for(const version of ['0.0.0-dev.123.1.gabcdef0','1.2.3']){
  const packed=spawnSync('helm',['package','charts/tiket','--destination',packages,'--version',version,'--app-version',version],{encoding:'utf8'});
  assert.equal(packed.status,0,packed.stderr);
  const archive=path.join(packages,'tiket-'+version+'.tgz');
  const metadata=spawnSync('helm',['show','chart',archive],{encoding:'utf8'});assert.equal(metadata.status,0,metadata.stderr);
  const chart=YAML.parse(metadata.stdout);assert.equal(chart.version,version);assert.equal(chart.appVersion,version);
  const rendered=spawnSync('helm',['template','tiket',archive],{encoding:'utf8'});assert.equal(rendered.status,0,rendered.stderr);
  const deployment=YAML.parseAllDocuments(rendered.stdout).map(d=>d.toJSON()).find(d=>d?.kind==='Deployment');
  assert.equal(deployment.spec.template.spec.containers[0].image,'ghcr.io/wiesmak/tiket:'+version);
 }
}finally{fs.rmSync(packages,{recursive:true,force:true});}
console.log('Helm validation matrix passed');
