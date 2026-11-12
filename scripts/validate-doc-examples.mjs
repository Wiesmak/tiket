import {createRequire} from 'node:module';
import {readFileSync} from 'node:fs';
import path from 'node:path';
const require = createRequire(import.meta.url);
const loaderRequire = createRequire(require.resolve('@grpc/proto-loader'));
const protobuf = loaderRequire('protobufjs');
const root = new protobuf.Root();
const base = path.resolve('tiket-contracts/src/main/proto');
root.resolvePath = (_, target) => path.resolve(base, target);
root.loadSync(['system', 'administration', 'betting', 'account'].map(n => 'pl/umamusume/tiket/api/v1/' + n + '.proto'), {keepCase: true});
root.resolveAll();
function check(type, object, where) {
  if (!object || typeof object !== 'object' || Array.isArray(object)) throw Error(where + ': expected object');
  for (const key of Object.keys(object)) if (!type.fields[key]) throw Error(where + ': unknown field ' + key);
  for (const oneof of Object.values(type.oneofs || {})) {
    if (oneof.oneof.filter(k => object[k] !== undefined).length > 1) throw Error(where + ': conflicting oneof');
  }
  for (const [key, value] of Object.entries(object)) {
    const field = type.fields[key];
    for (const item of field.repeated ? value : [value]) {
      if (field.resolvedType instanceof protobuf.Type) check(field.resolvedType, item, where + '.' + key);
      if (field.resolvedType instanceof protobuf.Enum && !(item in field.resolvedType.values)) throw Error(where + ': unknown enum ' + item);
    }
  }
  const message = type.fromObject(object);
  const error = type.verify(message);
  if (error) throw Error(where + ': ' + error);
  type.decode(type.encode(message).finish());
}
const manifest = JSON.parse(readFileSync('docs/api/fixtures/manifest.json', 'utf8'));
for (const entry of manifest) {
  const [serviceName, methodName] = entry.method.split('/');
  const method = root.lookupService(serviceName).methods[methodName];
  if (!method) throw Error(entry.method);
  check(method.resolvedRequestType, JSON.parse(readFileSync('docs/api/fixtures/' + entry.file, 'utf8')), entry.file);
}
console.log('Validated ' + manifest.length + ' request fixtures against checked-in Protobuf schemas.');
