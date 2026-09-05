// Isolated fixture: dev/ui_stream_review.clj, nREPL 17432.
const {chromium}=require('playwright');
const {execFileSync}=require('node:child_process');
const assert=require('node:assert/strict');
const repl=code=>execFileSync('clj-nrepl-eval',['-p','17432',code],{encoding:'utf8'});
(async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});
 const page=await browser.newPage({viewport:{width:390,height:844}});
 let sid;
 try {
  sid=repl(`(def history-sid (:id (agent.persistence.sqlite/create-session! post-store "History pagination review")))
    (dotimes [n 605] (agent.persistence.sqlite/append-message! post-store history-sid "user" (str "history-row-" n))) history-sid`).match(/=> "([^"]+)"/g).at(-1).slice(4,-1);
  const errors=[]; page.on('pageerror',e=>errors.push(e.message));
  await page.goto(`http://127.0.0.1:17431/chat/${sid}`);
  await page.locator('#session-messages-panel article.message').first().waitFor();
  const rows=async()=>(await page.locator('#session-messages-panel article.message .message-content').allTextContents()).map(v=>v.trim());
  const seen=new Set(await rows());
  let pages=1;
  while(await page.getByRole('button',{name:'Older',exact:true}).count()) {
   const old=await rows();
   await page.getByRole('button',{name:'Older',exact:true}).click();
   await page.waitForFunction(first=>document.querySelector('#session-messages-panel article.message .message-content')?.textContent.trim()!==first,old[0]);
   const values=await rows(); assert.ok(values.length<=60 && values.length>0);
   values.forEach(v=>{assert.ok(!seen.has(v),`duplicate ${v}`);seen.add(v);});pages++;
  }
  assert.equal(seen.size,605); assert.equal(pages,11);
  assert.ok(seen.has('history-row-0'));assert.ok(seen.has('history-row-604'));
  await page.getByRole('button',{name:'Newer',exact:true}).click();
  await page.waitForFunction(()=>document.querySelectorAll('#session-messages-panel article.message').length===60);
  assert.equal((await rows())[0],'history-row-5');
  await page.locator('#session-messages-panel').evaluate(e=>{e.releaseBottom();e.scrollTop=100;});
  const before=await rows(), scroll=await page.locator('#session-messages-panel').evaluate(e=>e.scrollTop);
  repl(`(agent.persistence.sqlite/append-message! post-store "${sid}" "assistant" "new-live-answer")
    ((:event-sink (:system post-review)) {:event-type :message-end :entity-type :session :entity-id "${sid}" :payload {:final? true}})`);
  await page.waitForTimeout(300);
  assert.deepEqual(await rows(),before);
  assert.equal(await page.locator('#session-messages-panel').evaluate(e=>e.scrollTop),scroll);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await page.screenshot({path:'target/ui-review/history-390.png'});
  await page.locator('[data-chat-scroll-bottom]').click();
  await page.getByText('new-live-answer',{exact:true}).waitFor();
  repl('(reset! stream-chunks 1200)');
  const response=page.waitForResponse(r=>r.url().endsWith('/ui/chat')&&r.request().method()==='POST');
  await page.locator('#chat-form textarea').fill('Resume streaming after reading history');
  await page.locator('#chat-form button[type=submit]').click();
  await page.waitForFunction(()=>document.querySelector('#streaming-message .message-content')?.textContent.includes('word'));
  await page.getByRole('button',{name:'Older',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#session-messages-panel')?.dataset.history==='true');
  const during=await rows();
  await (await response).finished();
  await page.waitForFunction(()=>!document.querySelector('#chat-form button[type=submit]').disabled);
  assert.deepEqual(await rows(),during);
  await page.locator('[data-chat-scroll-bottom]').click();
  await page.waitForFunction(()=>document.querySelector('#session-messages-panel')?.textContent.includes('word1199'));
  assert.equal(await page.locator('#session-messages-panel article.message').filter({hasText:'word1199'}).count(),1);
  assert.deepEqual(errors,[]);
  console.log(JSON.stringify({pages,uniqueMessages:seen.size,preservedScroll:scroll,streamingResumed:true}));
 } finally {
  await browser.close();
  repl("(reset! stream-chunks 120)");
  if(sid) repl(`(agent.persistence.sqlite.common/with-connection post-store #(agent.persistence.sqlite.common/execute! % ["delete from sessions where id = ?" "${sid}"]))`);
 }
})();
