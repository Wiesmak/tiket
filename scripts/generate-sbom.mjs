import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
const image=process.argv[2]||'tiket:kind';
const output=path.resolve(process.argv[3]||'build/phase8-artifacts');
const bootJar=fs.readdirSync('tiket-backend/build/libs').find(x=>x.endsWith('-boot.jar'));
if(!bootJar)throw Error('Build the backend bootJar before generating SBOMs');
const version=bootJar.slice('tiket-backend-'.length,-'-boot.jar'.length);
fs.mkdirSync(output,{recursive:true});
for(const module of ['backend','core','contracts','print-api','print-console']){
 const libs='tiket-'+module+'/build/libs';
 for(const file of fs.readdirSync(libs).filter(x=>x.endsWith('.jar')))fs.copyFileSync(path.join(libs,file),path.join(output,file));
}
const syft='anchore/syft:v1.38.0@sha256:825cad3a952c87676a6d07e9a3bb05ac9c401d598360070e970aa46d54c1727e';
for(const [source,name,mounts] of [
 ['docker:'+image,'image', ['-v','/var/run/docker.sock:/var/run/docker.sock']],
 ['dir:/scan','jvm',['-v',output+':/scan:ro']]
]){
 const sourceMetadata=name==='jvm'?['--source-name','tiket-jvm-artifacts','--source-version',version]:[];
 const r=spawnSync('docker',['run','--rm',...mounts,'-v',output+':/out',syft,source,...sourceMetadata,'-o','cyclonedx-json=/out/'+name+'.cdx.json'],{stdio:'inherit',timeout:300000});
 if(r.status!==0)throw Error('SBOM generation failed: '+name);
}
const files=fs.readdirSync(output).filter(x=>/\.(jar|json|tgz)$/.test(x)).sort();
fs.writeFileSync(path.join(output,'SHA256SUMS'),files.map(file=>createHash('sha256').update(fs.readFileSync(path.join(output,file))).digest('hex')+'  '+file).join('\n')+'\n');
console.log('Generated unsigned local SBOMs and checksums in '+output);
