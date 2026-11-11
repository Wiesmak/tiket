import https from 'node:https';
import fs from 'node:fs';
import {createPublicKey} from 'node:crypto';
const key=createPublicKey(fs.readFileSync('/keys/signing.pem')).export({format:'jwk'});
Object.assign(key,{kid:'kind',alg:'RS256',use:'sig'});
https.createServer({key:fs.readFileSync('/keys/tls.key'),cert:fs.readFileSync('/keys/tls.crt')},(req,res)=>{
 res.setHeader('content-type','application/json');
 res.end(JSON.stringify({keys:[key]}));
}).listen(8443,'0.0.0.0');
