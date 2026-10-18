import sys, json
from playwright.sync_api import sync_playwright

HTML = "file:///home/claude/btchat/index.html?nosplash"
HTML_SPLASH = "file:///home/claude/btchat/index.html"
SHOTS = "/home/claude/btchat/test/"

MOCK = """
(() => {
  const M = window.__m = {perm:true, enabled:true, paired:%s, out:[], failWrites:false, connected:false};
  const ev = o => setTimeout(() => window.onBtEvent(JSON.stringify(o)), 5);
  window.__ev = ev;
  const peerOf = a => (M.paired.find(p => p.address === a) || {name:a});
  window.BTBridge = {
    info: () => JSON.stringify({ok:true, version:1, supported:true, enabled:M.enabled, hasPermission:M.perm, sdk:34}),
    requestPermission: () => { M.perm = true; ev({type:'perm', granted:true}); return '{"ok":true}'; },
    pairedDevices: () => JSON.stringify({ok:true, devices:M.paired}),
    listen: () => { M.out.push({ctl:'listen'}); ev({type:'state', state:'listening'}); return '{"ok":true}'; },
    connect: a => { M.out.push({ctl:'connect', addr:a}); ev({type:'state', state:'connecting', peer:{name:peerOf(a).name, address:a}}); return '{"ok":true}'; },
    send: (ref, line) => {
      if (!M.connected) return JSON.stringify({ok:false, error:'not-connected'});
      if (!M.failWrites) M.out.push({ctl:'line', line});
      ev({type:'write', ref, ok:!M.failWrites, error: M.failWrites ? 'broken pipe' : undefined});
      return '{"ok":true}';
    },
    close: () => { M.out.push({ctl:'close'}); M.connected = false; ev({type:'state', state:'closed', detail:'You disconnected.'}); return '{"ok":true}'; },
    openBluetoothSettings: () => { M.out.push({ctl:'settings'}); return '{"ok":true}'; }
  };
})();
"""

results = []
def check(name, cond, extra=""):
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + (("  -> " + str(extra)) if (extra and not cond) else ""))

def pump(a, b, st, rounds=4):
    for _ in range(rounds):
        for src, dst in ((a, b), (b, a)):
            outs = src.evaluate("() => { const o = window.__m.out; window.__m.out = []; return o; }")
            for o in outs:
                ctl = o["ctl"]
                if ctl == "listen":
                    st["host"] = src
                elif ctl == "connect":
                    if st.get("host") is dst:
                        host_peer = "{name:'Phone B',address:'BB:BB'}"
                        join_peer = "{name:'Phone A',address:'AA:AA'}"
                        src.evaluate("() => {window.__m.connected=true; window.__ev({type:'state',state:'connected',peer:%s});}" % join_peer)
                        dst.evaluate("() => {window.__m.connected=true; window.__ev({type:'state',state:'connected',peer:%s});}" % host_peer)
                    else:
                        src.evaluate("() => window.__ev({type:'state',state:'error',peer:{name:'Phone A',address:'AA:AA'},detail:'Could not connect: no phone is hosting right now.'})")
                elif ctl == "line":
                    dst.evaluate("(l) => window.__ev({type:'line', line:l})", o["line"])
                elif ctl == "close":
                    if dst.evaluate("() => window.__m.connected"):
                        dst.evaluate("() => {window.__m.connected=false; window.__ev({type:'state',state:'closed',detail:'The other phone closed the connection.'});}")
        a.wait_for_timeout(40)

with sync_playwright() as p:
    browser = p.chromium.launch()
    errors = []

    def new_phone(ctx, paired):
        pg = ctx.new_page()
        pg.on("pageerror", lambda e: errors.append(str(e)))
        pg.add_init_script(MOCK % json.dumps(paired))
        pg.goto(HTML)
        return pg

    vp = {"width": 390, "height": 844}

    # 0. no bridge at all (plain browser)
    ctx0 = browser.new_context(viewport=vp, device_scale_factor=2)
    pg0 = ctx0.new_page(); pg0.on("pageerror", lambda e: errors.append(str(e)))
    pg0.goto(HTML); pg0.wait_for_timeout(200)
    check("no bridge: banner says Bluetooth unavailable", "not available" in pg0.inner_text("#banner"))
    check("no bridge: host/join disabled", pg0.is_disabled("#b-host") and pg0.is_disabled("#b-join"))
    check("no bridge: bridge row says Missing", pg0.inner_text("#r-bridge") == "Missing")
    pg0.screenshot(path=SHOTS + "01_no_bridge.png")
    ctx0.close()

    ctxA = browser.new_context(viewport=vp, device_scale_factor=2)
    ctxB = browser.new_context(viewport=vp, device_scale_factor=2)
    A = new_phone(ctxA, [{"name": "Phone B", "address": "BB:BB"}])
    B = new_phone(ctxB, [{"name": "Phone A", "address": "AA:AA"}])
    A.wait_for_timeout(200); B.wait_for_timeout(200)
    st = {}

    check("bridge found on both", A.inner_text("#r-bridge") == "Found" and B.inner_text("#r-bridge") == "Found")
    check("bridge buttons enabled", not A.is_disabled("#b-host"))
    A.screenshot(path=SHOTS + "02_home.png")

    # permission needed path
    A.evaluate("() => { window.__m.perm = false; }")
    A.wait_for_timeout(3300)
    check("permission needed shown", A.inner_text("#r-perm") == "Needed" and A.is_visible("#fix-perm"))
    A.click("#b-host"); A.wait_for_timeout(150)
    check("host without permission asks, does not start", A.evaluate("() => document.querySelector('#v-chat').classList.contains('active')") is False)
    A.wait_for_timeout(200)
    check("permission granted afterwards", A.evaluate("() => window.__m.perm") is True)

    # host + join
    A.click("#b-host"); pump(A, B, st)
    check("host: waiting status", "Waiting" in A.inner_text("#c-status"))
    check("host: composer disabled while waiting", A.is_disabled("#input"))
    B.click("#b-join"); B.wait_for_timeout(100)
    check("join list shows paired phone", "Phone A" in B.inner_text("#paired"))
    B.screenshot(path=SHOTS + "03_join_list.png")
    B.click("#paired button"); pump(A, B, st)
    check("join: B connected", B.inner_text("#c-status") == "Connected")
    check("host: A connected, peer name from native", A.inner_text("#c-status") == "Connected" and A.inner_text("#c-name") == "Phone B")
    check("composer enabled when connected", not A.is_disabled("#input") and not B.is_disabled("#input"))

    # messages both ways
    B.fill("#input", "hello from B"); B.click("#send"); pump(A, B, st)
    check("A received B's message", "hello from B" in A.inner_text("#msgs"))
    check("B bubble shows delivered ticks", "✓✓" in B.inner_text("#msgs"))
    nasty = "<img src=x onerror=window.__pwned=1> line2\nline3"
    A.fill("#input", nasty); A.click("#send"); pump(A, B, st)
    check("B received A's message literally", "<img src=x" in B.inner_text("#msgs"))
    check("no HTML injection", B.evaluate("() => !window.__pwned && !document.querySelector('#msgs img')"))
    check("multiline kept", B.evaluate("() => document.querySelector('#msgs .msg.them p').textContent.includes('\\n')"))
    B.screenshot(path=SHOTS + "04_chat_B.png")
    A.screenshot(path=SHOTS + "05_chat_A.png")

    # write failure is shown honestly, retry works
    B.evaluate("() => { window.__m.failWrites = true; }")
    B.fill("#input", "this will fail"); B.click("#send"); pump(A, B, st)
    check("failed write marked 'Not sent'", "Not sent" in B.inner_text("#msgs"))
    check("failed message not received by A", "this will fail" not in A.inner_text("#msgs"))
    B.evaluate("() => { window.__m.failWrites = false; }")
    B.click(".msg.me.failed"); pump(A, B, st)
    check("retry delivers", "this will fail" in A.inner_text("#msgs") and "Not sent" not in B.inner_text("#msgs"))

    # disconnect
    A.click("#c-menu"); A.click("#m-btns button:has-text('Disconnect')"); pump(A, B, st)
    check("B told about disconnect", B.inner_text("#c-status") == "Disconnected" and "closed the connection" in B.inner_text("#cbanner"))
    check("B composer disabled after disconnect", B.is_disabled("#input") and B.is_disabled("#send"))
    check("B has Reconnect button", B.is_visible("#cbanner button:has-text('Reconnect')"))
    B.screenshot(path=SHOTS + "06_disconnected.png")

    # reconnect error when nobody hosts
    st["host"] = None
    B.click("#cbanner button:has-text('Reconnect')"); pump(A, B, st)
    check("reconnect with nobody hosting shows honest error", "no phone is hosting" in B.inner_text("#cbanner") and "Could not connect" in B.inner_text("#c-status"))

    # persistence across reload (B's page is in its own context, so own storage)
    B.reload(); B.wait_for_timeout(250)
    check("saved chat listed after reload", "Phone A" in B.inner_text("#threads") and "this will fail" in B.inner_text("#threads"))
    B.click("#threads button"); B.wait_for_timeout(100)
    check("history restored", "hello from B" in B.inner_text("#msgs") and "this will fail" in B.inner_text("#msgs"))
    check("history view says not connected", B.inner_text("#c-status") == "Not connected" and B.is_disabled("#input"))

    # reconnect for real
    A.click("#cbanner button:has-text('Host instead')") if A.is_visible("#cbanner button:has-text('Host instead')") else None
    pump(A, B, st)
    B.click("#cbanner button:has-text('Reconnect')"); pump(A, B, st)
    check("reconnected", B.inner_text("#c-status") == "Connected" and A.inner_text("#c-status") == "Connected")
    B.fill("#input", "back again"); B.click("#send"); pump(A, B, st)
    check("message after reconnect works", "back again" in A.inner_text("#msgs"))

    # leaving chat while connected asks first and closes
    B.click("#c-back"); B.wait_for_timeout(80)
    check("leave asks for confirmation", B.is_visible("#modal"))
    B.click("#m-btns button:has-text('Disconnect')"); pump(A, B, st)
    check("left to home", B.evaluate("() => document.querySelector('#v-home').classList.contains('active')"))
    check("peer notified on leave", A.inner_text("#c-status") == "Disconnected")

    # ---- splash ----
    ctxS = browser.new_context(viewport=vp, device_scale_factor=2)
    S = ctxS.new_page(); S.on("pageerror", lambda e: errors.append(str(e)))
    S.goto(HTML_SPLASH)
    S.wait_for_timeout(700);  S.screenshot(path=SHOTS + "10_splash_a.png")
    S.wait_for_timeout(1000); S.screenshot(path=SHOTS + "11_splash_b.png")
    S.wait_for_timeout(1100); S.screenshot(path=SHOTS + "12_splash_c.png")
    check("splash visible while playing", S.evaluate("() => !!document.getElementById('splash')"))
    check("app buttons blocked by splash", S.evaluate("() => { const r=document.querySelector('#b-host').getBoundingClientRect(); const t=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2); return t && t.closest('#splash')!==null; }"))
    S.wait_for_timeout(2600)
    check("splash auto-removes", S.evaluate("() => !document.getElementById('splash')"))
    check("home visible after splash", S.is_visible("#b-host"))
    S.screenshot(path=SHOTS + "13_after_splash.png")
    S.reload(); S.wait_for_timeout(500)
    S.click("#splash"); S.wait_for_timeout(1200)
    check("tap skips splash", S.evaluate("() => !document.getElementById('splash')"))
    S.reload(); S.wait_for_timeout(400)
    S.evaluate("() => window.onNativeBack()"); S.wait_for_timeout(1200)
    check("Android back skips splash", S.evaluate("() => !document.getElementById('splash')"))
    rctx = browser.new_context(viewport=vp, reduced_motion="reduce")
    R = rctx.new_page(); R.goto(HTML_SPLASH); R.wait_for_timeout(2300)
    check("reduced motion: splash short", R.evaluate("() => !document.getElementById('splash')"))

    check("no page errors", not errors, errors)
    browser.close()

failed = [n for n, ok in results if not ok]
print("\n%d checks, %d failed" % (len(results), len(failed)))
sys.exit(1 if failed else 0)
