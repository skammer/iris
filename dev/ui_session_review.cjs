// Uses the isolated dev/ui_review.clj fixture. Removes only sessions seeded here.
const {chromium}=require('playwright');
const {execFileSync}=require('node:child_process');
const assert=require('node:assert/strict');
const repl=code=>execFileSync('clj-nrepl-eval',['-p','17332',code],{encoding:'utf8'});
(async()=>{
 const b=await chromium.launch({channel:'chrome',headless:true});
 try {
  repl(`(assert (= "target/ui-review/review.db" (:path review-store)))
        (def review-page-sessions (mapv (fn [n] (agent.persistence.sqlite/create-session! review-store (str "Page review " n) {:metadata {:project-id (format "review-project-%03d" n)}})) (range 120)))`);
  const p=await b.newPage({viewport:{width:390,height:844}});const errors=[];
  p.on('pageerror',e=>errors.push(e.message));
  await p.goto('http://127.0.0.1:17331/chat');
  await p.locator('.chat-sessions-toggle').click();
  await p.locator('.session-link').first().waitFor();
  assert.equal(await p.locator('.session-link').count(),50);
  await p.getByRole('button',{name:'Next sessions'}).click();
  await p.waitForFunction(()=>document.querySelector('.session-pagination')?.textContent.includes('51–100'));
  assert.equal(await p.locator('.session-link').count(),51);
  await p.locator('.session-link').nth(1).click();
  await p.waitForFunction(()=>document.querySelector('.chat-sessions-toggle')?.getAttribute('aria-expanded')==='false');
  await p.locator('.chat-sessions-toggle').click();
  assert.match(await p.locator('.session-pagination').textContent(),/51–100/);
  await p.evaluate(()=>document.activeElement?.blur());
  await p.waitForResponse(r=>r.url().includes('/ui/sessions?')&&r.url().includes('offset=50'),{timeout:20000});
  assert.match(await p.locator('.session-pagination').textContent(),/51–100/);
  const project=p.locator('#create-session-form input[name=project_id]');
  await project.fill('review-project-119');
  await p.waitForFunction(()=>document.querySelector('#session-project-ids option')?.value==='review-project-119');
  assert.equal(await p.locator('#session-project-ids option').count(),1);
  assert.equal(await project.inputValue(),'review-project-119');
  await p.locator('#chat-form textarea').fill('Draft survives title change');
  const sid=await p.locator('#session-detail-panel').getAttribute('data-session-id');
  repl(`(agent.persistence.sqlite.common/with-connection review-store #(agent.persistence.sqlite.common/execute! % ["update sessions set title = ? where id = ?" "Renamed while browsing" "${sid}"]))
        ((:event-sink (:system review)) {:event-type :session.title.updated :entity-type :session :entity-id "${sid}" :payload {}})`);
  await p.waitForFunction(()=>document.querySelector('#chat-session-title')?.textContent==='Renamed while browsing');
  assert.equal(await p.locator('#chat-form textarea').inputValue(),'Draft survives title change');
  assert.match(await p.locator('.session-pagination').textContent(),/51–100/);
  await p.screenshot({path:'target/ui-review/session-pages-390.png'});
  assert.deepEqual(errors,[]);
  console.log('50-row pages; selected row; 15s refresh; prefix autocomplete; title update preserves draft and page: passed.');
 } finally {
  repl(`(when (bound? #'review-page-sessions) (doseq [session review-page-sessions] (agent.persistence.sqlite.common/with-connection review-store #(agent.persistence.sqlite.common/execute! % ["delete from sessions where id = ?" (:id session)]))))`);
  await b.close();
 }
})();
