// Removes ad placements from YouTube player responses before the player reads them.
// Requirement ADB-003. Tested by tests/js/youtube-player-prune.test.mjs (Verifies: ADB-003).
//
// Runs at document start (see ADB-006). It wraps the three ways the player gets its configuration:
// JSON.parse, Response.prototype.json and the ytInitialPlayerResponse global.
(function () {
    "use strict";

    const AD_KEYS = ["adPlacements", "adSlots", "playerAds", "adBreakHeartbeatParams"];

    function prune(value) {
        if (value === null || typeof value !== "object")
            return value;
        const targets = [value];
        if (value.playerResponse && typeof value.playerResponse === "object")
            targets.push(value.playerResponse);
        for (const target of targets) {
            for (const key of AD_KEYS) {
                if (Object.prototype.hasOwnProperty.call(target, key))
                    delete target[key];
            }
        }
        return value;
    }

    const originalParse = JSON.parse;
    JSON.parse = function (text, reviver) {
        return prune(originalParse.call(this, text, reviver));
    };

    if (typeof Response !== "undefined" && Response.prototype && Response.prototype.json) {
        const originalJson = Response.prototype.json;
        Response.prototype.json = function () {
            return originalJson.call(this).then(prune);
        };
    }

    let initialPlayerResponse = prune(globalThis.ytInitialPlayerResponse);
    try {
        Object.defineProperty(globalThis, "ytInitialPlayerResponse", {
            configurable: true,
            get() { return initialPlayerResponse; },
            set(value) { initialPlayerResponse = prune(value); },
        });
    } catch (e) {
        // Already defined as non-configurable: nothing more we can do.
    }

    globalThis.__ladybirdAndroidPrune = prune;
})();
