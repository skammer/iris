const {chromium}=require('playwright');
const {execFileSync}=require('node:child_process');
const assert=require('node:assert/strict');
const port=process.env.IRIS_STREAM_NREPL_PORT || '17432';
const repl=code=>execFileSync('clj-nrepl-eval',['-p',port,code],{encoding:'utf8'});
(async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});
 const errors=[];
 try {
  assert.match(repl(`(count @(deref #'agent.api.handlers.ui/ui-session-streams))`), /=> 0/);
  const sender=await browser.newPage(), observer=await browser.newPage();
  for(const p of [sender,observer]) {
   p.on('pageerror',e=>errors.push(e.message));
   await p.goto('http://127.0.0.1:17431/chat');
   await p.locator('#session-messages-panel').waitFor();
   await p.evaluate(()=>{
    window.streamSamples=[];
    new MutationObserver(()=>{
     const text=document.querySelector('#streaming-message .message-content')?.textContent.trimEnd();
     if(text && text!==window.streamSamples.at(-1)?.text) window.streamSamples.push({time:performance.now(),text});
    }).observe(document.querySelector('#session-messages-panel'),{subtree:true,childList:true,characterData:true});
   });
  }
  repl(`(require 'agent.ui :reload) (def stream-review-original agent.ui/session-messages-fragment)
        (def stream-review-full (atom 0))
        (alter-var-root #'agent.ui/session-messages-fragment (constantly (fn [system sid & [opts]] (swap! stream-review-full inc) (stream-review-original system sid (or opts {})))))`);
  const response=sender.waitForResponse(r=>r.url().endsWith('/ui/chat')&&r.request().method()==='POST');
  await sender.locator('#chat-form textarea').fill('Stream the numbered words');
  const started=Date.now();
  await sender.locator('#chat-form button[type=submit]').click();
  await Promise.all([sender,observer].map(p=>p.waitForFunction(()=>window.streamSamples.length>=2)));
  const reply=await response;
  const body=await reply.text();
  const finished=Date.now();
  await sender.waitForFunction(()=>!document.querySelector('#chat-form button[type=submit]').disabled);
  await observer.waitForFunction(()=>document.querySelector('#streaming-message')?.hidden===true);
  for(const p of [sender,observer]) {
   const samples=await p.evaluate(()=>window.streamSamples);
   assert.ok(samples.length>=2 && samples.length<65,`partial count ${samples.length}`);
   for(let i=1;i<samples.length;i++) assert.ok(samples[i].text.startsWith(samples[i-1].text),'stream regressed');
   assert.ok(samples.at(-1).text.includes('word'));
   assert.equal(await p.locator('#streaming-message').evaluate(e=>e.hidden),true);
   await p.locator('#chat-stop').waitFor({state:'hidden'});
   console.log(JSON.stringify({client:p===sender?'sender':'observer',partialUpdates:samples.length}));
  }
  assert.equal((body.match(/id="session-messages-panel"/g)||[]).length,2);
  assert.ok((body.match(/HISTORY_SENTINEL/g)||[]).length<=120);
  assert.ok(body.includes('word119'));
  assert.deepEqual(errors,[]);
  console.log(JSON.stringify({elapsedMs:finished-started,postBytes:Buffer.byteLength(body),fullPatches:2}));
  assert.match(repl(`@stream-review-full`), /=> 4/);
  assert.match(repl(`@(deref #'agent.api.handlers.ui/ui-chat-posts)`), /=> \{\}/);
  await browser.close();
  for(let n=0;n<30;n++) {
   if(repl(`(count @(deref #'agent.api.handlers.ui/ui-session-streams))`).includes('=> 0')) break;
   await new Promise(resolve=>setTimeout(resolve,100));
  }
  assert.match(repl(`(count @(deref #'agent.api.handlers.ui/ui-session-streams))`), /=> 0/);
  console.log('4 total history renders; both SSE subscriptions released');
 } finally {
  repl(`(when (bound? #'stream-review-original) (alter-var-root #'agent.ui/session-messages-fragment (constantly stream-review-original)))`);
  await browser.close();
 }
})();
