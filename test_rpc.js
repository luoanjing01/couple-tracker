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
    ["[1] register_user_v2 (P_ PREFIX NEW FUNC)", "/rest/v1/rpc/register_user_v2",
     { p_username: "nodetest_v2x", p_password: "testpass123", p_nickname: "V2New", p_gender: "male" }],
    ["[2] verify_login_v2 (P_ PREFIX NEW FUNC)", "/rest/v1/rpc/verify_login_v2",
     { p_username: "nodetest_v2x", p_password: "testpass123" }],
    ["[3] register_user (NO PREFIX, EXISTING APK)", "/rest/v1/rpc/register_user",
     { username: "nodetest_v1x", password: "testpass123", nickname: "V1Old", gender: "female" }],
    ["[4] verify_login (NO PREFIX, EXISTING APK)", "/rest/v1/rpc/verify_login",
     { username: "nodetest_v1x", password: "testpass123" }],
    ["[5] USER xccz02 / qde1234567890", "/rest/v1/rpc/verify_login",
     { username: "xccz02", password: "qde1234567890" }],
    ["[6] USER 123456789 / qde1234567890", "/rest/v1/rpc/verify_login",
     { username: "123456789", password: "qde1234567890" }]
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
