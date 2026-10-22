const {chromium}=require('playwright');
exports.openBrowser=()=>process.env.IRIS_BROWSER_WS
 ?chromium.connect(process.env.IRIS_BROWSER_WS)
 :chromium.launch({headless:true,channel:process.env.IRIS_BROWSER_CHANNEL || undefined});
