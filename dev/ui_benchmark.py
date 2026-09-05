import sqlite3,json,time,statistics,pathlib,tempfile,urllib.request,gzip
root=pathlib.Path('target/ui-review')
f=tempfile.NamedTemporaryFile(prefix='benchmark-',suffix='.db',dir=root,delete=False);f.close()
source=sqlite3.connect(root/'review.db');db=sqlite3.connect(f.name,cached_statements=0);source.backup(db);source.close()
db.execute("INSERT INTO sessions(id,title,kind,created_at) VALUES ('benchmark','Large history','chat','2026-09-05T00:00:00Z')")
for i in range(10000):
 meta=json.dumps({'usage':{'tokens':100,'prompt-tokens':70,'completion-tokens':30},'activated-at':f'2026-09-05T01:{i//60:03}:{i%60:02}Z'})
 mid=db.execute("INSERT INTO messages(session_id,role,content,metadata_json,created_at) VALUES ('benchmark','assistant',?,?,?)",('Text '*400,meta,'2026-09-05T00:00:00Z')).lastrowid
 db.execute("INSERT INTO session_entries(id,session_id,type,payload_json,created_at) VALUES (?,'benchmark','message',?,'2026-09-05T00:00:00Z')",(f'bench-{i}',json.dumps({'message-id':mid,'content-blocks':[{'type':'text','text':'Text '*400}]})))
db.commit()
queries={
 'recent_60':("SELECT id,role,content,tool_calls,tool_call_id,metadata_json,excluded_from_context,created_at FROM messages WHERE session_id=? ORDER BY coalesce(json_extract(metadata_json,'$.activated-at'),created_at) DESC,id DESC LIMIT 60",['benchmark']),
 'rich_60':("SELECT payload_json FROM session_entries WHERE session_id=? AND type='message' AND cast(json_extract(payload_json,'$.\"message-id\"') as integer) IN ("+','.join('?'*60)+')',['benchmark']+list(range(mid-59,mid+1)))
}
def measure():
 out={}
 for name,(sql,params) in queries.items():
  times=[]
  for _ in range(25):
   t=time.perf_counter();rows=db.execute(sql,params).fetchall();times.append((time.perf_counter()-t)*1000)
  out[name]={'median_ms':round(statistics.median(times),3),'rows':len(rows),'plan':[r[3] for r in db.execute('EXPLAIN QUERY PLAN '+sql,params)]}
 return out
after=measure()
db.execute('DROP INDEX idx_messages_session_display_order');db.execute('DROP INDEX idx_session_entries_message_id');db.commit()
before=measure()
http={}
for route in ['/chat','/ui/shell?tab=chat','/public/app.css','/public/web-components.js']:
 req=urllib.request.Request('http://127.0.0.1:17331'+route,headers={'Accept-Encoding':'gzip'})
 with urllib.request.urlopen(req) as r:
  body=r.read();http[route]={'encoding':r.headers.get('Content-Encoding'),'wire_bytes':len(body),'decoded_bytes':len(gzip.decompress(body)) if r.headers.get('Content-Encoding')=='gzip' else len(body)}
result={'messages':10000,'before':before,'after':after,'http':http}
(root/'benchmark.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
