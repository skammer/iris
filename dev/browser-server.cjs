// Runs only on the requested browser host; SSH forwards both fixture ports.
const {chromium}=require('playwright');
(async()=>{
 const server=await chromium.launchServer({headless:true,channel:'chrome',host:'127.0.0.1',port:Number(process.argv[2])});
 console.log(server.wsEndpoint());
 const close=async()=>{await server.close();process.exit(0);};
 process.on('SIGTERM',close);process.on('SIGINT',close);
 process.stdin.resume();process.stdin.on('end',close);
})().catch(e=>{console.error(e);process.exit(1)});
