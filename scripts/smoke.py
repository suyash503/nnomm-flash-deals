# Smoke test against `docker compose up`: create a deal on 8080, claim on 8081, replay, pay, read back.
# Usage: python scripts/smoke.py   (reads JWT_SECRET / WEBHOOK_SECRET from the environment)
import hmac, hashlib, base64, json, os, time, uuid, urllib.request
JWT=os.environ.get("JWT_SECRET","local-dev-jwt-secret-change-me"); WH=os.environ.get("WEBHOOK_SECRET","local-dev-webhook-secret-change-me")
b64=lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()
def tok(claims):
    h=b64(json.dumps({"alg":"HS256","typ":"JWT"}).encode()); p=b64(json.dumps(claims).encode())
    s=b64(hmac.new(JWT.encode(), f"{h}.{p}".encode(), hashlib.sha256).digest()); return f"{h}.{p}.{s}"
def call(m,url,body=None,headers={}):
    r=urllib.request.Request(url,data=body.encode() if body else None,method=m,headers={"Content-Type":"application/json",**headers})
    try:
        with urllib.request.urlopen(r) as x: return x.status, x.read().decode(), dict(x.headers)
    except urllib.error.HTTPError as e: return e.code, e.read().decode(), dict(e.headers)
exp=int(time.time())+3600; rest=str(uuid.uuid4()); cust=str(uuid.uuid4())
admin=tok({"sub":str(uuid.uuid4()),"role":"restaurant_admin","restaurant_id":rest,"exp":exp})
c=tok({"sub":cust,"role":"customer","exp":exp})
now=time.time(); iso=lambda t: time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t))
s,b,_=call("POST","http://localhost:8080/v1/deals",json.dumps({"title":"Chicken biryani box","price_paise":9900,"stock_total":50,"starts_at":iso(now-60),"ends_at":iso(now+3600)}),{"Authorization":"Bearer "+admin}); print("create deal (8080):",s,b)
deal=json.loads(b)["id"]
s,b,_=call("POST",f"http://localhost:8081/v1/deals/{deal}/claims",'{"quantity":2}',{"Authorization":"Bearer "+c,"Idempotency-Key":"smoke-1"}); print("claim (8081):",s,b)
claim=json.loads(b)["id"]
s,b,h=call("POST",f"http://localhost:8080/v1/deals/{deal}/claims",'{"quantity":2}',{"Authorization":"Bearer "+c,"Idempotency-Key":"smoke-1"}); print("retry (8080):",s,"replayed=",h.get("Idempotent-Replayed"))
ev=json.dumps({"event_id":"evt_"+uuid.uuid4().hex,"type":"payment.succeeded","claim_id":claim,"amount_paise":19800,"occurred_at":iso(time.time())})
t=str(int(time.time())); sig=hmac.new(WH.encode(), (t+"."+ev).encode(), hashlib.sha256).hexdigest()
print("webhook (8081):",*call("POST","http://localhost:8081/v1/webhooks/payments",ev,{"X-Payment-Signature":f"t={t},v1={sig}"})[:2])
print("get claim (8080):",*call("GET",f"http://localhost:8080/v1/claims/{claim}",None,{"Authorization":"Bearer "+c})[:2])
print("get deal (8081):",*call("GET",f"http://localhost:8081/v1/deals/{deal}",None,{"Authorization":"Bearer "+c})[:2])
