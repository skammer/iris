// One command owns isolated fixtures, browser checks and teardown.
const {spawn,execFileSync}=require('node:child_process');
const fs=require('node:fs');
const net=require('node:net');
const path=require('node:path');
const assert=require('node:assert/strict');
const freePort=()=>new Promise(resolve=>{const s=net.createServer();s.listen(0,'127.0.0.1',()=>{const port=s.address().port;s.close(()=>resolve(port));});});
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const children=[];
const run=(command,args,env,stdio='inherit')=>spawn(command,args,{env,stdio});
async function ready(url,child,log){
 for(let i=0;i<180;i++){
  if(child.exitCode!==null)throw new Error(`Fixture exited: ${log}`);
  try{if((await fetch(url+'/health')).ok)return;}catch{}
  await delay(500);
 }
 throw new Error(`Fixture startup timed out: ${log}`);
}
(async()=>{
 fs.mkdirSync('target/ui-browser',{recursive:true});
 const dir=fs.mkdtempSync('target/ui-browser/run-');
 const env={...process.env,IRIS_CONFIG_DIR:path.resolve(dir,'config'),IRIS_DATA_DIR:path.resolve(dir,'data'),IRIS_UI_REVIEW_DIR:path.resolve(dir),
  IRIS_UI_PORT:String(await freePort()),IRIS_UI_NREPL_PORT:String(await freePort()),IRIS_STREAM_PORT:String(await freePort()),IRIS_STREAM_NREPL_PORT:String(await freePort())};
 fs.mkdirSync(env.IRIS_CONFIG_DIR,{recursive:true});fs.mkdirSync(env.IRIS_DATA_DIR,{recursive:true});
 try{
  for(const [file,port] of [['dev/ui_review.clj',env.IRIS_UI_PORT],['dev/ui_stream_review.clj',env.IRIS_STREAM_PORT]]){
   const log=path.join(dir,path.basename(file)+'.log');const fd=fs.openSync(log,'w');
   const child=run('clojure',['-M:test',file],env,['ignore',fd,fd]);fs.closeSync(fd);children.push(child);
   await ready('http://127.0.0.1:'+port,child,log);
   console.log(`Fixture ready: ${file}`);
  }
  if(env.IRIS_BROWSER_HOST){
   const host=env.IRIS_BROWSER_HOST;
   assert.match(host,/^[a-zA-Z0-9.-]+$/);
   const ssh=['-o','BatchMode=yes','-o','ClearAllForwardings=yes'];
   const remote=execFileSync('ssh',[...ssh,host,'mktemp -d /tmp/iris-ui-browser-XXXXXX'],{encoding:'utf8'}).trim();
   assert.match(remote,/^\/tmp\/iris-ui-browser-[a-zA-Z0-9]+$/);
   const archive=path.join(dir,'browser.tar.gz');
   execFileSync('tar',['-czf',archive,'node_modules/playwright','node_modules/playwright-core','dev/browser-server.cjs'],{env:{...process.env,COPYFILE_DISABLE:'1'}});
   execFileSync('scp',['-q',archive,`${host}:${remote}/browser.tar.gz`]);
   execFileSync('ssh',[...ssh,host,`tar --warning=no-unknown-keyword -xzf ${remote}/browser.tar.gz -C ${remote}`]);
   const browserPort=await freePort();
   // Explicit -R/-L forwards require ClearAllForwardings=no here. Ignore any
   // host-config forwarding by using a separate connection with -F /dev/null.
   const config=execFileSync('ssh',['-G',host],{encoding:'utf8'});
   const value=name=>config.match(new RegExp('^'+name+' (.+)$','m'))?.[1];
   const identityArgs=[...config.matchAll(/^identityfile (.+)$/gm)].flatMap(m=>['-i',m[1]]);
   const tunnel=run('ssh',['-F','/dev/null','-l',value('user'),'-p',value('port'),...identityArgs,'-o','BatchMode=yes','-o','ExitOnForwardFailure=yes',
    '-R',`${env.IRIS_UI_PORT}:127.0.0.1:${env.IRIS_UI_PORT}`,
    '-R',`${env.IRIS_STREAM_PORT}:127.0.0.1:${env.IRIS_STREAM_PORT}`,
    '-L',`${browserPort}:127.0.0.1:${browserPort}`,value('hostname'),
    `cd ${remote} && node dev/browser-server.cjs ${browserPort}`],env,['pipe','pipe','inherit']);
   children.push(tunnel);
   env.IRIS_BROWSER_WS=await new Promise((resolve,reject)=>{
    let text='';const timer=setTimeout(()=>reject(new Error('Remote browser startup timeout')),30000);
    tunnel.stdout.on('data',chunk=>{text+=chunk;const match=text.match(/ws:\/\/[^\s]+/);if(match){clearTimeout(timer);resolve(match[0]);}});
    tunnel.on('exit',code=>{clearTimeout(timer);reject(new Error(`Remote browser exited: ${code}`));});
   });
   console.log(`Browser host: ${host}`);
  }
  for(const script of (env.IRIS_UI_TESTS?.split(',') || ['ui_review.cjs','ui_session_review.cjs','ui_stream_review.cjs','ui_history_review.cjs','ui_frontend_review.cjs'])){
   const child=run(process.execPath,[path.join('dev',script)],env);children.push(child);
   console.log(`Running ${script}`);
   const code=await new Promise((resolve,reject)=>{child.on('exit',resolve);child.on('error',reject);});
   assert.equal(code,0,script);
  }
  console.log(`Browser checks passed. Artifacts: ${dir}`);
 }finally{
  await Promise.all(children.filter(c=>c.exitCode===null && c.signalCode===null).map(c=>new Promise(resolve=>{c.once('exit',resolve);c.kill('SIGTERM');const timer=setTimeout(()=>c.kill('SIGKILL'),5000);timer.unref();})));
 }
})().catch(error=>{console.error(error);process.exitCode=1;});
