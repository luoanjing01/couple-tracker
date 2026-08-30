const https = require("https");
const URL = "https://gvytqbgangyjjurekyid.supabase.co";
const KEY = "sb_publishable_TmlnyTou7Z7JGt3vNP3TTw_3-KkCiCM";
function post(path, data) {
  return new Promise((resolve) => {
    const body = JSON.stringify(data);
    const req = https.request(URL + path, {
      method: "POST",
      headers: {
        "apikey": KEY,
        "Content-Type": "application/json",
        "Accept": "application/json",
        "Content-Length": Buffer.byteLength(body)
      },
      timeout: 20000
    }, (res) => {
      let chunks = "";
      res.on("data", (c) => chunks += c);
      res.on("end", () => resolve({ status: res.statusCode, body: chunks }));
    });
    req.on("timeout", () => { req.destroy(new Error("TIMEOUT")); });
    req.on("error", (e) => resolve({ status: 0, body: "ERR: " + e.message }));
    req.write(body);
    req.end();
  });
}
(async () => {
  const cases = [
    ["[1] register_user WITH P_ PREFIX (matches schema cache!)", "/rest/v1/rpc/register_user",
     { p_username: "http_match_ok1", p_password: "testpass123", p_nickname: "命中缓存啦", p_gender: "male" }],
    ["[2] verify_login WITH P_ PREFIX (matches schema cache!)", "/rest/v1/rpc/verify_login",
     { p_username: "http_match_ok1", p_password: "testpass123" }],
    ["[3] verify_login WRONG PASSWORD", "/rest/v1/rpc/verify_login",
     { p_username: "http_match_ok1", p_password: "wrongpass123" }],
    ["[4] register_user DUPLICATE USERNAME", "/rest/v1/rpc/register_user",
     { p_username: "http_match_ok1", p_password: "testpass123", p_nickname: "重复", p_gender: "female" }],
  ];
  for (const [name, path, data] of cases) {
    console.log("--- " + name + " ---");
    try {
      const r = await post(path, data);
      console.log("  HTTP " + r.status);
      console.log("  BODY: " + (r.body || "").substring(0, 500));
    } catch (e) {
      console.log("  EXC: " + e.message);
    }
    console.log("");
  }
})();
