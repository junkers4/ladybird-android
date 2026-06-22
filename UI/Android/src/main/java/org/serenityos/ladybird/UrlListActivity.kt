package org.serenityos.ladybird

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.serenityos.ladybird.databinding.ActivityUrlListBinding

/**
 * Full-screen history/bookmarks page styled after Chrome's history UI:
 * toolbar, an accent "Delete browsing data" row (history only), and a
 * list of favicon + title + url rows with per-row delete.
 */
class UrlListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUrlListBinding
    private lateinit var adapter: UrlListAdapter
    private var isHistory = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUrlListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        isHistory = intent.getStringExtra(EXTRA_MODE) != MODE_BOOKMARKS

        binding.listToolbar.title = getString(if (isHistory) R.string.history_title else R.string.bookmarks_title)
        binding.listToolbar.setNavigationOnClickListener { finish() }
        binding.emptyText.text = getString(if (isHistory) R.string.history_empty else R.string.bookmarks_empty)

        binding.clearAllRow.visibility = if (isHistory) View.VISIBLE else View.GONE
        binding.clearAllDivider.visibility = binding.clearAllRow.visibility
        binding.clearAllRow.setOnClickListener {
            startActivity(Intent(this, DeleteBrowsingDataActivity::class.java))
        }

        binding.urlList.layoutManager = LinearLayoutManager(this)
        adapter = UrlListAdapter(
            mutableListOf(),
            onClick = { row -> openInBrowser(row.url) },
            onDelete = { row ->
                if (isHistory) HistoryStore(this).remove(row.url)
                else BookmarksStore(this).remove(row.url)
                reload()
            }
        )
        binding.urlList.adapter = adapter
        reload()
    }

    private fun reload() {
        val rows = if (isHistory)
            HistoryStore(this).all().map { UrlRow(it.url, it.title) }
        else
            BookmarksStore(this).all().map { UrlRow(it.url, it.title) }
        adapter.submit(rows)
        binding.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.urlList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openInBrowser(url: String) {
        val intent = Intent(this, LadybirdActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_HISTORY = "history"
        const val MODE_BOOKMARKS = "bookmarks"
    }
}
