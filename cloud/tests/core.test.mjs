import test from 'node:test';
import assert from 'node:assert/strict';
import {numeric,pageNumber,regionName,presentationReferences,redact,encryptSession,decryptSession,TOOLS} from '../lib/core.mjs';
test('only known regions and numeric identifiers are accepted',()=>{assert.equal(numeric('123'),'123');for(const x of ['../1','https://evil/1','1?foo=2',1])assert.throws(()=>numeric(x));assert.throws(()=>regionName('__proto__'));assert.throws(()=>pageNumber(0));assert.equal(pageNumber(0,0),0);});
test('do not infer a presentation from classroom or courseware identifiers',()=>assert.deepEqual(presentationReferences({classroom_id:3,courseware_id:5,content:{presentation_id:7},more:[{presentationId:'8'}]}),['7','8']));
test('redaction removes nested credentials',()=>assert.deepEqual(redact({Auth:'x',items:[{lessonToken:'x',title:'test'}]}),{items:[{title:'test'}]}));
test('session encryption is owner-bound and randomized',async()=>{const key='a'.repeat(64);const a=await encryptSession('secret-session',key,'user-1:standard');const b=await encryptSession('secret-session',key,'user-1:standard');assert.notEqual(a,b);assert.equal(await decryptSession(a,key,'user-1:standard'),'secret-session');await assert.rejects(()=>decryptSession(a,key,'user-2:standard'));await assert.rejects(()=>decryptSession(a,'b'.repeat(64),'user-1:standard'));});
test('all six account tools declare schemas and no automatic current-page claim',()=>{assert.equal(TOOLS.length,6);assert.deepEqual(TOOLS.find(x=>x.name==='read_slide').inputSchema.required,['presentation_id','page']);assert.ok(TOOLS.every(t=>t.annotations.readOnlyHint));});


test('detect image content even when WeChat sends nonstandard MIME headers',async()=>{
 const {imageMime}=await import('../lib/core.mjs');
 const png=new Uint8Array(24);png.set([137,80,78,71,13,10,26,10]);
 assert.equal(imageMime(png),'image/png');
 assert.equal(imageMime(Uint8Array.from([255,216,255,224])),'image/jpeg');
 assert.equal(imageMime(new TextEncoder().encode('GIF89a'+'\0'.repeat(7))),'image/gif');
 assert.equal(imageMime(new TextEncoder().encode('RIFF0000WEBP0000')),'image/webp');
 assert.throws(()=>imageMime(new TextEncoder().encode('<html>not an image</html>')));
 assert.throws(()=>imageMime(Uint8Array.from([137,80,78,71])));
});
