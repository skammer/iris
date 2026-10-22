// Minimal test-only nREPL client. No globally installed CLI required in CI.
const net=require('node:net');
const [port,code]=process.argv.slice(2);
const encode=value=>{
 if(typeof value==='string'){const b=Buffer.from(value);return Buffer.concat([Buffer.from(b.length+':'),b]);}
 return Buffer.concat([Buffer.from('d'),...Object.entries(value).flatMap(([k,v])=>[encode(k),encode(v)]),Buffer.from('e')]);
};
const incomplete=Symbol('incomplete');
function decode(buffer,offset=0){
 if(offset>=buffer.length)throw incomplete;
 const type=buffer[offset];
 if(type===100||type===108){
  const value=type===100?{}:[];let cursor=offset+1;
  while(buffer[cursor]!==101){
   let key;[key,cursor]=decode(buffer,cursor);
   if(type===100){let item;[item,cursor]=decode(buffer,cursor);value[key]=item;}else value.push(key);
  }
  return [value,cursor+1];
 }
 if(type===105){const end=buffer.indexOf(101,offset+1);if(end<0)throw incomplete;return [Number(buffer.toString('utf8',offset+1,end)),end+1];}
 const colon=buffer.indexOf(58,offset);if(colon<0)throw incomplete;
 const length=Number(buffer.toString('ascii',offset,colon)),end=colon+1+length;
 if(end>buffer.length)throw incomplete;
 return [buffer.toString('utf8',colon+1,end),end];
}
let buffer=Buffer.alloc(0),done=false;
const socket=net.connect({host:'127.0.0.1',port:Number(port)},()=>socket.write(encode({op:'eval',id:'ui-test',ns:'user',code})));
socket.setTimeout(60000,()=>socket.destroy(new Error('nREPL evaluation timed out')));
socket.on('data',chunk=>{
 buffer=Buffer.concat([buffer,chunk]);
 try{
  while(buffer.length){
   let result,end;try{[result,end]=decode(buffer);}catch(e){if(e===incomplete)return;throw e;}
   buffer=buffer.subarray(end);
   if(result.out)process.stdout.write(result.out);
   if(result.value!==undefined)console.log('=> '+result.value);
   if(result.err)process.stderr.write(result.err);
   if(result.status?.some(s=>['eval-error','error','unknown-op'].includes(s)))process.exitCode=1;
   if(result.status?.includes('done')){done=true;socket.end();}
  }
 }catch(error){socket.destroy(error);}
});
socket.on('error',error=>{console.error(error.message);process.exitCode=1;});
socket.on('close',()=>{if(!done)process.exitCode=1;});
