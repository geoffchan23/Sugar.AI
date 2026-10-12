#!/usr/bin/env python3
"""Manage the private Alexa skill "Ray" through the Skill Management API (SMAPI).

Credentials stay off the repo: ~/.config/cgm-tools/amazon-lwa.json (Login with Amazon
tokens + the ask-cli client they were issued to) and alexa.env (ALEXA_SKILL_ID).

  tools/alexa-skill.py push-model            upload server/alexa/.../<locale>.json for en-CA and en-US, wait for the build
  tools/alexa-skill.py simulate "ask ray …"  run one utterance through Alexa (real signed request to the server)
  tools/alexa-skill.py simulate-file f.txt   one utterance per line
"""
import json, os, sys, time, urllib.error, urllib.parse, urllib.request

CFG = os.path.expanduser("~/.config/cgm-tools")
LWA = os.path.join(CFG, "amazon-lwa.json")
MODELS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "server", "alexa", "skill-package", "interactionModels", "custom")
LOCALES = ["en-CA", "en-US"]

tok = json.load(open(LWA))
SKILL = next(l.split("=", 1)[1].strip() for l in open(os.path.join(CFG, "alexa.env")) if l.startswith("ALEXA_SKILL_ID="))


def refresh():
    data = urllib.parse.urlencode({"grant_type": "refresh_token", "refresh_token": tok["refresh_token"],
                                   "client_id": tok["client_id"], "client_secret": tok["client_secret"]}).encode()
    tok.update(json.load(urllib.request.urlopen("https://api.amazon.com/auth/o2/token", data)))
    old = os.umask(0o077); json.dump(tok, open(LWA, "w")); os.umask(old)


def call(method, path, body=None, retry=True):
    req = urllib.request.Request("https://api.amazonalexa.com" + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": tok["access_token"], "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            t = r.read().decode()
            return r.status, (json.loads(t) if t else {})
    except urllib.error.HTTPError as e:
        t = e.read().decode()
        if e.code == 401 and retry:
            refresh()
            return call(method, path, body, False)
        return e.code, (json.loads(t) if t.strip().startswith("{") else t)


def push_model():
    for loc in LOCALES:
        s, b = call("PUT", f"/v1/skills/{SKILL}/stages/development/interactionModel/locales/{loc}", json.load(open(os.path.join(MODELS, f"{loc}.json"))))
        if s >= 300:
            sys.exit(f"{loc}: {s} {b}")
    for _ in range(100):
        s, st = call("GET", f"/v1/skills/{SKILL}/status?resource=interactionModel")
        ims = st.get("interactionModel", {})
        states = {k: v.get("lastUpdateRequest", {}).get("status") for k, v in ims.items()}
        if states and all(v != "IN_PROGRESS" for v in states.values()):
            print("build", states)
            for v in ims.values():
                if v["lastUpdateRequest"]["status"] != "SUCCEEDED":
                    print(json.dumps(v["lastUpdateRequest"], indent=1)[:2000])
            return all(v == "SUCCEEDED" for v in states.values())
        time.sleep(3)
    sys.exit("build timed out")


def simulate(text, locale="en-CA"):
    s, b = call("POST", f"/v2/skills/{SKILL}/stages/development/simulations",
                {"session": {"mode": "FORCE_NEW_SESSION"}, "input": {"content": text}, "device": {"locale": locale}})
    if s >= 300:
        return {"text": text, "status": f"HTTP {s}", "detail": b}
    for _ in range(45):
        time.sleep(1)
        s, r = call("GET", f"/v2/skills/{SKILL}/stages/development/simulations/{b['id']}")
        if r.get("status") != "IN_PROGRESS":
            break
    res = r.get("result", {})
    out = {"text": text, "status": r.get("status"), "intent": None, "speech": None, "error": (res.get("error") or {}).get("message")}
    for x in (res.get("skillExecutionInfo") or {}).get("invocations") or []:
        req = x.get("invocationRequest", {}).get("body", {}).get("request", {})
        out["intent"] = req.get("intent", {}).get("name") or req.get("type")
        out["speech"] = x.get("invocationResponse", {}).get("body", {}).get("response", {}).get("outputSpeech", {}).get("text")
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "push-model":
        sys.exit(0 if push_model() else 1)
    elif cmd == "simulate":
        print(json.dumps(simulate(" ".join(sys.argv[2:]))))
    elif cmd == "simulate-file":
        for line in open(sys.argv[2]):
            if line.strip():
                print(json.dumps(simulate(line.strip())), flush=True)
                time.sleep(3)
    else:
        sys.exit(__doc__)
