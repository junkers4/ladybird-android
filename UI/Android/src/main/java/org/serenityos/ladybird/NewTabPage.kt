/*
 * Copyright (c) 2024, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package org.serenityos.ladybird

import android.net.Uri

/**
 * Builds a self-contained, offline New Tab page styled after Chrome/Vanadium:
 * a centred wordmark and a rounded search field. Submitting either performs a
 * search with the user's configured engine or navigates directly to a typed
 * URL. Served as a `data:` URL so it never touches the network (and therefore
 * never triggers reCAPTCHA), and the activity blanks the omnibox while it is
 * shown, exactly like a real browser's new-tab surface.
 */
object NewTabPage {
    fun dataUrl(searchTemplate: String, dark: Boolean): String {
        val html = htmlFor(searchTemplate, dark)
        return "data:text/html;charset=utf-8," + Uri.encode(html)
    }

    private fun htmlFor(searchTemplate: String, dark: Boolean): String {
        // Embed the template as a JSON-safe string literal.
        val template = searchTemplate.replace("\\", "\\\\").replace("\"", "\\\"")
        val palette = if (dark) {
            """
    --bg: #202124;
    --field: #2f3033;
    --field-focus: #35363a;
    --text: #e8eaed;
    --muted: #9aa0a6;
    --outline: #3c4043;
    --accent: #a8c7fa;"""
        } else {
            """
    --bg: #ffffff;
    --field: #f1f3f4;
    --field-focus: #ffffff;
    --text: #1f1f1f;
    --muted: #5f6368;
    --outline: #dadce0;
    --accent: #0b57d0;"""
        }
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<title>New Tab</title>
<style>
  :root {$palette
  }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html, body { height: 100%; margin: 0; }
  body {
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, Roboto, "Segoe UI", Arial, sans-serif;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: flex-start;
    padding-top: 30vh;
  }
  form {
    width: min(92%, 560px);
  }
  .search {
    display: flex;
    align-items: center;
    height: 52px;
    padding: 0 18px;
    background: var(--field);
    border: 1px solid transparent;
    border-radius: 26px;
    transition: background .15s, border-color .15s, box-shadow .15s;
  }
  .search:focus-within {
    background: var(--field-focus);
    border-color: var(--outline);
    box-shadow: 0 1px 6px rgba(32,33,36,.28);
  }
  .search svg { width: 20px; height: 20px; fill: var(--muted); flex: 0 0 auto; }
  input {
    flex: 1 1 auto;
    border: 0;
    outline: 0;
    background: transparent;
    color: var(--text);
    font-size: 16px;
    margin-left: 12px;
    min-width: 0;
  }
  input::placeholder { color: var(--muted); }
</style>
</head>
<body>
  <form id="f" autocomplete="off" onsubmit="return go();">
    <div class="search">
      <svg viewBox="0 0 24 24"><path d="M15.5 14h-.79l-.28-.27a6.5 6.5 0 1 0-.7.7l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0A4.5 4.5 0 1 1 14 9.5 4.5 4.5 0 0 1 9.5 14z"/></svg>
      <input id="q" type="text" inputmode="search" autocapitalize="off" autocorrect="off"
             spellcheck="false" placeholder="Search or type URL" aria-label="Search or type URL">
    </div>
  </form>
<script>
  var TEMPLATE = "$template";
  function go() {
    var q = document.getElementById('q').value.trim();
    if (!q) return false;
    var dest;
    if (/^[a-z][a-z0-9+.-]*:\/\//i.test(q)) {
      dest = q;
    } else {
      var looksUrl = !/\s/.test(q) && (q.indexOf('.') > -1 || q === 'localhost');
      dest = looksUrl ? 'https://' + q : TEMPLATE.replace('%s', encodeURIComponent(q));
    }
    location.href = dest;
    return false;
  }
  document.getElementById('q').focus();
</script>
</body>
</html>
        """.trimIndent()
    }
}
