package com.example.cashierapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var dbHelper: ProductDatabaseHelper
    private lateinit var recyclerView: RecyclerView
    private val historyList = ArrayList<TransactionGroup>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        dbHelper = ProductDatabaseHelper(this)
        recyclerView = findViewById(R.id.recyclerViewHistory)
        recyclerView.layoutManager = LinearLayoutManager(this)

        loadHistory()
    }

    private fun loadHistory() {
        val db = dbHelper.readableDatabase
        // Group by transaction_id
        val cursor = db.rawQuery("""
            SELECT transaction_id, sale_date, SUM(price * quantity) as total_price 
            FROM history 
            GROUP BY transaction_id 
            ORDER BY sale_date DESC
        """.trimIndent(), null)

        historyList.clear()
        if (cursor.moveToFirst()) {
            do {
                val tid = cursor.getString(0)
                val date = cursor.getString(1)
                val total = cursor.getDouble(2)
                
                // Fetch items for this transaction
                val items = mutableListOf<HistoryItem>()
                val itemCursor = db.rawQuery("SELECT product_name, price, category, quantity FROM history WHERE transaction_id = ?", arrayOf(tid))
                if (itemCursor.moveToFirst()) {
                    do {
                        items.add(HistoryItem(
                            itemCursor.getString(0),
                            itemCursor.getDouble(1),
                            itemCursor.getString(2),
                            itemCursor.getInt(3)
                        ))
                    } while (itemCursor.moveToNext())
                }
                itemCursor.close()
                
                historyList.add(TransactionGroup(tid, date, total, items))
            } while (cursor.moveToNext())
        }
        cursor.close()
        recyclerView.adapter = HistoryGroupAdapter(historyList)
    }
}

data class HistoryItem(val name: String, val price: Double, val category: String, val quantity: Int)
data class TransactionGroup(val id: String, val date: String, val total: Double, val items: List<HistoryItem>)

class HistoryGroupAdapter(private val transactions: List<TransactionGroup>) : RecyclerView.Adapter<HistoryGroupAdapter.ViewHolder>() {
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val date: TextView = view.findViewById(R.id.txtHistoryDate)
        val items: TextView = view.findViewById(R.id.txtHistoryName) // Using existing IDs for simplicity
        val price: TextView = view.findViewById(R.id.txtHistoryPrice)
        val category: TextView = view.findViewById(R.id.txtHistoryCategory)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = 
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val txn = transactions[position]
        holder.date.text = txn.date
        holder.price.text = String.format(Locale.getDefault(), "Total: ₱%.2f", txn.total)
        
        val itemsSummary = txn.items.joinToString("\n") { "${it.quantity}x ${it.name}" }
        holder.items.text = itemsSummary
        holder.category.text = "ID: ${txn.id}"
    }

    override fun getItemCount() = transactions.size
}
