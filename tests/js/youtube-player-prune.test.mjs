// Verifies: ADB-003
// Run with: node --test tests/js
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("../../android/core/src/main/resources/io/github/junkers4/ladybird/core/adblock/youtube-player-prune.js", import.meta.url), "utf8");

function freshPage(initial) {
    class Response {
        constructor(body) { this.body = body; }
        json() { return Promise.resolve(JSON.parse(this.body)); }
    }
    const context = { JSON: { parse: JSON.parse, stringify: JSON.stringify }, Response, Promise, Object };
    context.globalThis = context;
    if (initial !== undefined)
        context.ytInitialPlayerResponse = initial;
    vm.createContext(context);
    vm.runInContext(source, context);
    return context;
}

const withAds = () => ({ videoDetails: { videoId: "x" }, adPlacements: [1], playerAds: [2], adSlots: [3] });

test("JSON.parse strips ad placements", () => {
    const page = freshPage();
    const parsed = page.JSON.parse(JSON.stringify(withAds()));
    assert.deepEqual(Object.keys(parsed), ["videoDetails"]);
});

test("nested playerResponse is pruned too", () => {
    const page = freshPage();
    const parsed = page.JSON.parse(JSON.stringify({ playerResponse: withAds(), other: 1 }));
    assert.deepEqual(Object.keys(parsed.playerResponse), ["videoDetails"]);
    assert.equal(parsed.other, 1);
});

test("fetch responses are pruned", async () => {
    const page = freshPage();
    const response = new page.Response(JSON.stringify(withAds()));
    const parsed = await response.json();
    assert.equal(parsed.adPlacements, undefined);
    assert.equal(parsed.videoDetails.videoId, "x");
});

test("ytInitialPlayerResponse is pruned whether set before or after", () => {
    const page = freshPage(withAds());
    assert.equal(page.ytInitialPlayerResponse.playerAds, undefined);
    page.ytInitialPlayerResponse = withAds();
    assert.equal(page.ytInitialPlayerResponse.adSlots, undefined);
    assert.equal(page.ytInitialPlayerResponse.videoDetails.videoId, "x");
});

test("non-objects and reviver pass through", () => {
    const page = freshPage();
    assert.equal(page.JSON.parse("5"), 5);
    assert.equal(page.JSON.parse("null"), null);
    assert.deepEqual(page.JSON.parse('{"a":1}', (k, v) => (k === "a" ? 2 : v)), { a: 2 });
});
