package com.example.cashierapp

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class InventoryActivity : AppCompatActivity() {

    lateinit var dbHelper: ProductDatabaseHelper
    lateinit var recyclerView: RecyclerView
    lateinit var adapter: InventoryAdapter
    var productList = ArrayList<Product>()
    private val categories = arrayOf("All", "Crochet", "Fuzzy Wire", "Beads", "General")
    private var ownerList = mutableListOf<String>()
    
    private lateinit var tvTotalAmountSold: TextView
    private lateinit var tvFilterTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inventory)

        dbHelper = ProductDatabaseHelper(this)
        recyclerView = findViewById(R.id.recyclerViewProducts)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        tvTotalAmountSold = findViewById(R.id.tvTotalAmountSold)
        tvFilterTitle = findViewById(R.id.tvFilterTitle)

        val spinnerFilter = findViewById<Spinner>(R.id.spinnerFilterCategory)
        val filterAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        filterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFilter.adapter = filterAdapter

        val spinnerOwner = findViewById<Spinner>(R.id.spinnerFilterOwner)
        setupOwnerSpinner(spinnerOwner)

        spinnerFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyFilters()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerOwner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyFilters()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnExportExcel).setOnClickListener {
            exportToExcel()
        }
    }

    private fun setupOwnerSpinner(spinner: Spinner) {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT DISTINCT owner_name FROM products", null)
        ownerList.clear()
        ownerList.add("All Owners")
        if (cursor.moveToFirst()) {
            do {
                ownerList.add(cursor.getString(0))
            } while (cursor.moveToNext())
        }
        cursor.close()

        val ownerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ownerList)
        ownerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = ownerAdapter
    }

    private fun applyFilters() {
        val cat = findViewById<Spinner>(R.id.spinnerFilterCategory).selectedItem.toString()
        val owner = findViewById<Spinner>(R.id.spinnerFilterOwner).selectedItem.toString()
        loadProducts(cat, owner)
        calculateTotalSold(cat, owner)
    }

    private fun calculateTotalSold(category: String, owner: String) {
        val db = dbHelper.readableDatabase
        // The total sold per owner should come from history, but here it looks like
        // the original request wanted it per product in the inventory too?
        // Let's use the history table for more accurate sales data per owner.
        var query = """
            SELECT SUM(h.price * h.quantity) 
            FROM history h 
            INNER JOIN products p ON h.product_name = p.name 
            WHERE 1=1
        """.trimIndent()
        val args = mutableListOf<String>()

        if (category != "All") {
            query += " AND h.category = ?"
            args.add(category)
        }
        if (owner != "All Owners") {
            query += " AND p.owner_name = ?"
            args.add(owner)
        }

        val cursor = db.rawQuery(query, args.toTypedArray())
        var total = 0.0
        if (cursor.moveToFirst()) {
            total = cursor.getDouble(0)
        }
        cursor.close()

        tvTotalAmountSold.text = String.format(Locale.getDefault(), "₱%.2f", total)
        
        val titleText = when {
            category != "All" && owner != "All Owners" -> "Total Sold: $owner ($category)"
            category != "All" -> "Total Sold: $category"
            owner != "All Owners" -> "Total Sold by $owner"
            else -> "Overall Total Sold"
        }
        tvFilterTitle.text = titleText
    }

    private fun loadProducts(filterCategory: String = "All", filterOwner: String = "All Owners") {
        val db = dbHelper.readableDatabase
        var query = "SELECT name, price, actual_stock, current_stock, barcode, image, category, owner_name FROM products WHERE 1=1"
        val args = mutableListOf<String>()

        if (filterCategory != "All") {
            query += " AND category = ?"
            args.add(filterCategory)
        }
        if (filterOwner != "All Owners") {
            query += " AND owner_name = ?"
            args.add(filterOwner)
        }
        
        val cursor = db.rawQuery(query, args.toTypedArray())

        productList.clear()

        if (cursor.moveToFirst()) {
            do {
                val name = cursor.getString(0)
                val price = cursor.getDouble(1)
                val actualStock = cursor.getInt(2)
                val currentStock = cursor.getInt(3)
                val barcode = cursor.getString(4)
                val imageBase64 = cursor.getString(5)
                val category = cursor.getString(6)
                val owner = cursor.getString(7)

                val productImage: Bitmap? = try {
                    if (!imageBase64.isNullOrEmpty()) {
                        val bytes = Base64.decode(imageBase64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } else null
                } catch (e: Exception) { null }

                productList.add(Product(name, price, actualStock, currentStock, barcode, productImage, category, owner))
            } while (cursor.moveToNext())
        }
        cursor.close()

        adapter = InventoryAdapter(productList, 
            onEdit = { product -> showEditDialog(product) },
            onDelete = { product -> showDeleteDialog(product) }
        )
        recyclerView.adapter = adapter
    }

    private fun exportToExcel() {
        if (productList.isEmpty()) {
            Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val workbook = XSSFWorkbook()
            val sheet = workbook.createSheet("Inventory")
            
            val headerRow = sheet.createRow(0)
            headerRow.createCell(0).setCellValue("Product Name")
            headerRow.createCell(1).setCellValue("Category")
            headerRow.createCell(2).setCellValue("Owner")
            headerRow.createCell(3).setCellValue("Price")
            headerRow.createCell(4).setCellValue("Actual Stock")
            headerRow.createCell(5).setCellValue("Current Stock")
            headerRow.createCell(6).setCellValue("Barcode")

            for (i in productList.indices) {
                val product = productList[i]
                val row = sheet.createRow(i + 1)
                row.createCell(0).setCellValue(product.name)
                row.createCell(1).setCellValue(product.category)
                row.createCell(2).setCellValue(product.ownerName)
                row.createCell(3).setCellValue(product.price)
                row.createCell(4).setCellValue(product.actualStock.toDouble())
                row.createCell(5).setCellValue(product.currentStock.toDouble())
                row.createCell(6).setCellValue(product.barcode)
            }

            val fileName = "Inventory_${System.currentTimeMillis()}.xlsx"
            val file = File(getExternalFilesDir(null), fileName)
            val outputStream = FileOutputStream(file)
            workbook.write(outputStream)
            outputStream.close()
            workbook.close()

            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Export Inventory"))

        } catch (e: Exception) {
            Toast.makeText(this, "Export failed!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditDialog(product: Product) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_product, null)
        val etName = dialogView.findViewById<EditText>(R.id.etEditName)
        val etOwner = dialogView.findViewById<EditText>(R.id.etEditOwnerName)
        val etPrice = dialogView.findViewById<EditText>(R.id.etEditPrice)
        val etActualStock = dialogView.findViewById<EditText>(R.id.etEditActualStock)
        val etCurrentStock = dialogView.findViewById<EditText>(R.id.etEditCurrentStock)
        val spinnerCategory = dialogView.findViewById<Spinner>(R.id.spinnerEditCategory)

        val editCategories = arrayOf("Crochet", "Fuzzy Wire", "Beads", "General")
        val editAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, editCategories)
        editAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCategory.adapter = editAdapter
        
        etName.setText(product.name)
        etOwner.setText(product.ownerName)
        etPrice.setText(product.price.toString())
        etActualStock.setText(product.actualStock.toString())
        etCurrentStock.setText(product.currentStock.toString())
        spinnerCategory.setSelection(editCategories.indexOf(product.category).coerceAtLeast(0))

        AlertDialog.Builder(this)
            .setTitle("Edit Product")
            .setView(dialogView)
            .setPositiveButton("Update") { _, _ ->
                val newName = etName.text.toString()
                val newOwner = etOwner.text.toString()
                val newPrice = etPrice.text.toString().toDoubleOrNull() ?: product.price
                val newActual = etActualStock.text.toString().toIntOrNull() ?: product.actualStock
                val newCurrentInput = etCurrentStock.text.toString().toIntOrNull()
                
                // Logic: If actual stock is increased, add that difference to current stock as well
                val diff = newActual - product.actualStock
                val newCurrent = if (newCurrentInput == null || newCurrentInput == product.currentStock) {
                    (product.currentStock + diff).coerceAtLeast(0)
                } else {
                    newCurrentInput
                }

                val newCategory = spinnerCategory.selectedItem.toString()

                updateProduct(product.barcode, newName, newPrice, newActual, newCurrent, newCategory, newOwner)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateProduct(barcode: String, name: String, price: Double, actual: Int, current: Int, category: String, owner: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("name", name)
            put("price", price)
            put("actual_stock", actual)
            put("current_stock", current)
            put("category", category)
            put("owner_name", owner)
        }
        db.update("products", values, "barcode = ?", arrayOf(barcode))
        applyFilters()
    }

    private fun deleteProduct(barcode: String) {
        val db = dbHelper.writableDatabase
        db.delete("products", "barcode = ?", arrayOf(barcode))
        applyFilters()
    }

    private fun showDeleteDialog(product: Product) {
        AlertDialog.Builder(this)
            .setTitle("Delete Product")
            .setMessage("Are you sure you want to delete ${product.name}?")
            .setPositiveButton("Delete") { _, _ -> deleteProduct(product.barcode) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

data class Product(
    val name: String,
    val price: Double,
    val actualStock: Int,
    val currentStock: Int,
    val barcode: String,
    val image: Bitmap?,
    val category: String,
    val ownerName: String
)

class InventoryAdapter(
    private val products: List<Product>,
    private val onEdit: (Product) -> Unit,
    private val onDelete: (Product) -> Unit
) : RecyclerView.Adapter<InventoryAdapter.ProductViewHolder>() {

    inner class ProductViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val txtName = view.findViewById<android.widget.TextView>(R.id.txtProductName)
        val txtPrice = view.findViewById<android.widget.TextView>(R.id.txtProductPrice)
        val txtStock = view.findViewById<android.widget.TextView>(R.id.txtProductStock)
        val txtCategory = view.findViewById<android.widget.TextView>(R.id.txtProductCategory)
        val txtOwner = view.findViewById<android.widget.TextView>(R.id.txtProductOwner)
        val imgProduct = view.findViewById<android.widget.ImageView>(R.id.imgProduct)
        val imgQR = view.findViewById<android.widget.ImageView>(R.id.imgQRCode)
        val btnEdit = view.findViewById<android.widget.ImageButton>(R.id.btnEditProduct)
        val btnDelete = view.findViewById<android.widget.ImageButton>(R.id.btnDeleteProduct)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ProductViewHolder {
        val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_product, parent, false)
        return ProductViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
        val product = products[position]
        holder.txtName.text = product.name
        holder.txtCategory.text = product.category
        holder.txtOwner.text = "Owner: ${product.ownerName}"
        holder.txtPrice.text = "₱${product.price}"
        holder.txtStock.text = "${product.currentStock} / ${product.actualStock}"
        holder.imgProduct.setImageBitmap(product.image ?: android.graphics.Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888))

        try {
            val bitMatrix = MultiFormatWriter().encode(product.barcode, BarcodeFormat.QR_CODE, 150, 150)
            holder.imgQR.setImageBitmap(BarcodeEncoder().createBitmap(bitMatrix))
        } catch (e: Exception) {}

        holder.btnEdit.setOnClickListener { onEdit(product) }
        holder.btnDelete.setOnClickListener { onDelete(product) }
    }

    override fun getItemCount(): Int = products.size
}