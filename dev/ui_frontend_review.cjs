const {openBrowser}=require('./browser.cjs');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const base='http://127.0.0.1:'+(process.env.IRIS_UI_PORT || '17331');
(async()=>{
 const browser=await openBrowser();
 try{
 const page=await browser.newPage({viewport:{width:1280,height:800}});
 page.setDefaultTimeout(15000);
 const errors=[],requests=[];
 page.on('pageerror',e=>{errors.push(e.message);console.error('Browser error:',e.message);});page.on('request',r=>requests.push(r.url()));
 await page.goto(base+'/chat');await page.locator('#chat-form').waitFor();
 assert.ok(!requests.some(u=>u.includes('cdn.jsdelivr')||u.includes('/katex/')),'local boot without math download');
 const input=page.locator('#chat-form textarea');
 let queries=0;
 await page.route('**/v1/slash-commands?**',async route=>{queries++;await route.fulfill({json:{items:[{name:'testing',description:'Test command'}]}});});
 await input.pressSequentially('/test',{delay:20});
 await page.locator('.skill-menu:not([hidden])').waitFor();
 assert.equal(queries,1,'debounced typing');
 await input.press('Escape');await page.waitForTimeout(200);
 assert.equal(await page.locator('.skill-menu').evaluate(el=>el.hidden),true,'Escape stays closed');
 // Resolve headers immediately, body later: exercises the second async boundary.
 await page.evaluate(()=>{
  window.realFetch=window.fetch;window.pendingBodies=[];
  window.fetch=(url,opts)=>String(url).includes('/v1/slash-commands?')
   ?Promise.resolve({ok:true,json:()=>new Promise(resolve=>window.pendingBodies.push({resolve,signal:opts.signal}))})
   :window.realFetch(url,opts);
 });
 await input.fill('/slow');await page.waitForFunction(()=>window.pendingBodies.length===1);
 await input.fill('');
 await page.evaluate(()=>window.pendingBodies.shift().resolve({items:[{name:'stale'}]}));
 await page.waitForTimeout(100);
 assert.equal(await page.locator('.skill-menu').evaluate(el=>el.hidden),true,'late body cannot reopen cleared menu');
 await input.fill('/old');await page.waitForFunction(()=>window.pendingBodies.length===1);
 await input.fill('/new');await page.waitForFunction(()=>window.pendingBodies.length===2);
 await page.evaluate(()=>window.pendingBodies[1].resolve({items:[{name:'new-result'}]}));
 await page.getByText('/new-result',{exact:true}).waitFor();
 await page.evaluate(()=>window.pendingBodies[0].resolve({items:[{name:'old-result'}]}));
 await page.waitForTimeout(100);assert.equal(await page.locator('.skill-menu__name').textContent(),'/new-result');
 // Old detached composer must neither keep its pending request nor its listeners.
 await input.fill('/detached');await page.waitForFunction(()=>window.pendingBodies.length===3);
 await page.evaluate(()=>{window.oldInput=document.querySelector('#chat-form textarea');});
 const sid=await page.locator('agent-chat-panel').getAttribute('data-session-id');
 await page.getByRole('tab',{name:'Overview',exact:true}).click();await page.locator('#dashboard-summary').waitFor();
 assert.equal(await page.evaluate(()=>window.pendingBodies[2].signal.aborted),true);
 await page.evaluate(()=>{window.oldInput.value='/leak';window.oldInput.dispatchEvent(new Event('input',{bubbles:true}));});
 await page.waitForTimeout(220);assert.equal(await page.evaluate(()=>window.pendingBodies.length),3);
 await page.evaluate(()=>{window.fetch=window.realFetch;window.oldInput=null;});
 await page.getByRole('tab',{name:'Chat',exact:true}).click();await input.waitFor();
 // Re-selecting the active session must not abort its unchanged live stream.
 const selected=page.locator('.session-link[aria-current=page]');
 await selected.click();
 await page.waitForTimeout(100);
 assert.equal(await page.evaluate(()=>window.irisChatStreamController.signal.aborted),false);
 await input.fill('draft survives recovery');
 await page.locator('#chat-form input[type=file]').setInputFiles({name:'draft.png',mimeType:'image/png',buffer:Buffer.from('fixture')});
 let navigations=0;page.on('framenavigated',()=>navigations++);
 await page.evaluate(()=>document.dispatchEvent(new CustomEvent('datastar-fetch',{detail:{type:'retries-failed',el:document.querySelector('agent-chat-panel')}})));
 await page.locator('[data-connection-status]:not([hidden])').waitFor();
 const recovered=page.waitForRequest(r=>r.url().includes('/ui/session/live?')&&r.url().includes('resync=true'));
 await page.getByRole('button',{name:'Reconnect',exact:true}).click();await recovered;
 await page.waitForFunction(()=>document.querySelector('agent-chat-panel').dataset.connection==='connected');
 assert.equal(navigations,0);assert.equal(await input.inputValue(),'draft survives recovery');
 assert.equal(await page.locator('#chat-form input[type=file]').evaluate(el=>el.files[0]?.name),'draft.png');
 await page.context().setOffline(true);
 const failed=page.waitForEvent('requestfailed',{predicate:r=>r.url().includes('/ui/session/live?')});
 await page.locator('agent-chat-panel').evaluate(el=>el.reconnect());
 await failed;
 await page.context().setOffline(false);
 await page.waitForFunction(()=>document.querySelector('agent-chat-panel').dataset.connection==='connected');
 assert.equal(await input.inputValue(),'draft survives recovery');

 await page.reload();await input.waitFor();assert.equal(await input.inputValue(),'draft survives recovery');
 // A different session gets its own draft, then the original draft returns.
 const other=await page.request.post(base+'/v1/sessions',{data:{title:'Draft isolation'}});
 const otherId=(await other.json()).data?.id || (await other.json()).id;
 assert.ok(otherId);
 await page.goto(base+'/chat/'+otherId);await input.waitFor();assert.equal(await input.inputValue(),'');
 await input.fill('other draft');await page.goto(base+'/chat/'+sid);await input.waitFor();assert.equal(await input.inputValue(),'draft survives recovery');
 await page.waitForFunction(()=>document.querySelector('agent-chat-panel').dataset.connection==='connected');
 // Stream one message alongside 60 unchanged messages. Count actual KaTeX calls.
 await page.evaluate(()=>{
  const stream=document.querySelector('chat-stream');
  for(let i=0;i<60;i++){const m=document.createElement('iris-markdown');m.className='message-content markdown';m.textContent='Unchanged '+i;stream.append(m);}
  const m=document.createElement('iris-markdown');m.id='math-review';m.className='message-content markdown';m.textContent='$x^2$';stream.append(m);
 });
 await page.locator('#math-review .katex').waitFor({state:'attached'}).catch(async e=>{console.error(await page.evaluate(()=>({math:document.querySelector('#math-review')?.outerHTML,katex:typeof window.katex,render:typeof window.renderMathInElement,scripts:[...document.scripts].map(s=>s.src)})));throw e;});
 await page.evaluate(()=>{const render=window.renderMathInElement;window.mathCalls=[];window.renderMathInElement=(el,opts)=>{window.mathCalls.push(el.id);return render(el,opts);};});
 const cdp=await page.context().newCDPSession(page);await cdp.send('Performance.enable');
 const before=(await cdp.send('Performance.getMetrics')).metrics;
 const start=Date.now();
 await page.evaluate(async()=>{for(let i=0;i<30;i++){document.getElementById('math-review').textContent=`$x_${i}$`;await new Promise(r=>setTimeout(r,25));}});
 const during=await page.evaluate(()=>window.mathCalls.length);
 await page.waitForTimeout(150);
 const calls=await page.evaluate(()=>window.mathCalls);
 assert.ok(during>0,'continuous stream does not starve math');assert.ok(calls.length<=16,'fixed coalescing deadline');assert.ok(calls.every(id=>id==='math-review'),'only changed message typeset');
 const after=(await cdp.send('Performance.getMetrics')).metrics;
 const metrics=Object.fromEntries(after.filter(m=>['LayoutCount','LayoutDuration','ScriptDuration','TaskDuration','JSHeapUsedSize'].includes(m.name)).map(m=>[m.name,m.value-(before.find(b=>b.name===m.name)?.value||0)]));
 fs.writeFileSync((process.env.IRIS_UI_REVIEW_DIR||'target/ui-review')+'/frontend-profile.json',JSON.stringify({elapsedMs:Date.now()-start,mutations:30,unchangedMessages:60,mathCalls:calls.length,metrics},null,2));
 await page.screenshot({path:(process.env.IRIS_UI_REVIEW_DIR||'target/ui-review')+'/frontend.png'});
 assert.deepEqual(errors,[]);
 console.log('Frontend: autocomplete races, debounce, teardown, draft isolation, recovery and scoped math passed.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
