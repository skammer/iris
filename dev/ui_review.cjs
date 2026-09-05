// Run with NODE_PATH pointing to an installed Playwright package. Uses dev/ui_review.clj.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
(async () => {
 const browser = await chromium.launch({headless:true,channel:'chrome'});
 const page=await browser.newPage(); const errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 for (const [w,h] of [[1440,900],[768,1024],[390,844],[320,640],[844,390]]) {
  await page.setViewportSize({width:w,height:h});
  for (const tab of ['chat','overview','cron','tools','memory','magi','logs']) {
   await page.goto(`http://127.0.0.1:17331/${tab}`);
   await page.locator('#workspace-content > *').first().waitFor();
   await page.evaluate(()=>document.fonts.ready);
   await page.waitForFunction(()=>!document.querySelector('.workspace-grid.is-entering'));
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,`${tab} ${w}`);
   const offenders=await page.locator('.workspace-content .panel').evaluateAll(els=>els.filter(e=>e.clientWidth>0 && e.scrollWidth>e.clientWidth+1).map(e=>({html:e.outerHTML.slice(0,1800),children:[...e.querySelectorAll('*')].filter(c=>c.getBoundingClientRect().right>e.getBoundingClientRect().right).map(c=>({tag:c.tagName,cls:c.className,width:c.clientWidth})).slice(0,15)})));
   if(offenders.length) console.log(JSON.stringify(offenders));
   assert.equal(await page.locator('.workspace-content .panel').evaluateAll(els=>els.some(e=>e.clientWidth>0 && e.scrollWidth>e.clientWidth+1)),false,`panel overflow ${tab} ${w}`);
   console.log(JSON.stringify({tab,w,panels:await page.locator('.workspace-content .panel').evaluateAll(els=>els.map(el=>({id:el.id,w:el.clientWidth,overflow:el.scrollWidth-el.clientWidth})))}));
   await page.screenshot({path:`target/ui-review/iris-${tab}-${w}.png`});
   if(tab==='chat') {
    const composer=await page.locator('#chat-form').boundingBox();
    assert.ok(composer.height>0 && composer.y+composer.height<=h,`composer ${w}`);
    const summary=page.locator('.tool-entry summary').first();
    await summary.click();
    await page.locator('.tool-entry[open] .tool-entry__section').first().waitFor();
    if(w<=760) await page.locator('.chat-sessions-toggle').click();
    await page.getByRole('tab',{name:'Ephemeral 12'}).click();
    await page.waitForFunction(()=>document.querySelector('.session-kind-tab[aria-selected=\"true\"]')?.textContent.includes('Ephemeral'));
    await page.waitForFunction(()=>document.querySelectorAll('.session-link').length===12);
    const spill=await page.locator('.session-link').evaluateAll(els=>els.some(e=>e.getBoundingClientRect().right>e.closest('#sessions-panel').getBoundingClientRect().right));
    assert.equal(spill,false,`ephemeral ${w}`);
    await page.screenshot({path:`target/ui-review/iris-ephemeral-${w}.png`});
    if(w<=760) {
     await page.locator('.session-link').first().click();
     assert.equal(await page.locator('.chat-sessions-toggle').getAttribute('aria-expanded'),'false');
    }
   }
  }
 }
 assert.deepEqual(errors,[]);
 const {execFileSync}=require('node:child_process');
 const repl=code=>execFileSync('clj-nrepl-eval',['-p','17332',code],{encoding:'utf8'});
 await page.setViewportSize({width:1280,height:800});
 await page.goto('http://127.0.0.1:17331/chat');
 await page.locator('#streaming-message').waitFor({state:'attached'});
 const proof=`Browser stream proof ${Date.now()}`;
 const sid=await page.locator('#session-detail-panel').getAttribute('data-session-id');
 repl("(require 'agent.chat :reload) (def review-original-streaming @#'agent.chat/streaming-state) (def review-stream (atom {:content \"\"})) (alter-var-root #'agent.chat/streaming-state (constantly (fn [_ _] @review-stream)))");
 try {
  repl(`(reset! review-stream {:content "${proof}"}) ((:event-sink (:system review)) {:event-type :message-update :entity-type :session :entity-id "${sid}" :payload {:delta "proof"}})`);
  await page.waitForFunction(proof=>document.querySelector('#streaming-message')?.textContent.includes(proof),proof);
  repl(`(agent.persistence.sqlite/append-message! review-store "${sid}" "assistant" "${proof}") (reset! review-stream {}) ((:event-sink (:system review)) {:event-type :message-end :entity-type :session :entity-id "${sid}" :payload {:final? true}})`);
  await page.waitForFunction(()=>document.querySelector('#streaming-message')?.hidden===true);
  assert.equal(await page.locator('.message-content').filter({hasText:proof}).count(),1);
  console.log('Streaming: live update and terminal replacement passed, no duplicate.');
 } finally { repl("(alter-var-root #'agent.chat/streaming-state (constantly review-original-streaming))"); }
 await browser.close();
})();
