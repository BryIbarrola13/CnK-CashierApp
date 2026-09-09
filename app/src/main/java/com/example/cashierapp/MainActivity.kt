package com.example.cashierapp

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import android.graphics.Color
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.google.zxing.integration.android.IntentIntegrator
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvTotalSold: TextView
    private lateinit var tvTotalSales: TextView
    
    // Preview Section
    private lateinit var productPreviewContainer: View
    private lateinit var ivPreviewImage: ImageView
    private lateinit var tvPreviewName: TextView
    private lateinit var tvPreviewCategory: TextView
    private lateinit var tvPreviewPrice: TextView
    private lateinit var tvPreviewStock: TextView
    private lateinit var btnCancelPreview: Button
    private lateinit var btnAddToCart: Button
    
    // Cart Section
    private lateinit var cartContainer: View
    private lateinit var cartItemsList: LinearLayout
    private lateinit var tvCartTotal: TextView
    private lateinit var etAmountPaid: EditText
    private lateinit var tvChange: TextView
    private lateinit var drawerLayout: DrawerLayout
    
    private val cart = mutableListOf<CartItem>()
    private var cartTotal: Double = 0.0
    private var lastScannedCode: String? = null

    companion object {
        private const val PICK_QR_REQUEST = 2
    }

    private lateinit var dbHelper: ProductDatabaseHelper

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { importDatabase(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dbHelper = ProductDatabaseHelper(this)

        // Setup Toolbar and Drawer
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        drawerLayout = findViewById(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        
        val toggle = ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.btn_check_only, R.string.btn_check_only)
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_add_product -> startActivity(Intent(this, AddProductActivity::class.java))
                R.id.nav_inventory -> startActivity(Intent(this, InventoryActivity::class.java))
                R.id.nav_history -> startActivity(Intent(this, HistoryActivity::class.java))
                R.id.nav_export_data -> exportDatabase()
                R.id.nav_import_data -> importLauncher.launch("*/*")
            }
            drawerLayout.closeDrawers()
            true
        }

        tvTotalSold = findViewById(R.id.tvTotalSold)
        tvTotalSales = findViewById(R.id.tvTotalSales)
        
        // Setup Preview
        productPreviewContainer = findViewById(R.id.productPreviewContainer)
        ivPreviewImage = findViewById(R.id.ivPreviewImage)
        tvPreviewName = findViewById(R.id.tvPreviewName)
        tvPreviewCategory = findViewById(R.id.tvPreviewCategory)
        tvPreviewPrice = findViewById(R.id.tvPreviewPrice)
        tvPreviewStock = findViewById(R.id.tvPreviewStock)
        btnCancelPreview = findViewById(R.id.btnCancelPreview)
        btnAddToCart = findViewById(R.id.btnAddToCart)
        
        // Setup Cart
        cartContainer = findViewById(R.id.cartContainer)
        cartItemsList = findViewById(R.id.cartItemsList)
        tvCartTotal = findViewById(R.id.tvCartTotal)
        etAmountPaid = findViewById(R.id.etAmountPaid)
        tvChange = findViewById(R.id.tvChange)

        updateStatsDisplay()

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            IntentIntegrator(this).apply {
                setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                setPrompt(getString(R.string.btn_scan_camera))
                setCameraId(0)
                setBeepEnabled(false)
                setBarcodeImageEnabled(false)
                setOrientationLocked(true)
                setCaptureActivity(com.journeyapps.barcodescanner.CaptureActivity::class.java)
                initiateScan()
            }
        }

        findViewById<Button>(R.id.btnScanGallery).setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, PICK_QR_REQUEST)
        }
        
        btnCancelPreview.setOnClickListener {
            productPreviewContainer.visibility = View.GONE
            lastScannedCode = null
        }
        
        btnAddToCart.setOnClickListener {
            lastScannedCode?.let { barcode ->
                addProductToCart(barcode)
                productPreviewContainer.visibility = View.GONE
                lastScannedCode = null
            }
        }
        
        findViewById<Button>(R.id.btnClearCart).setOnClickListener {
            cart.clear()
            updateCartUI()
        }
        
        findViewById<Button>(R.id.btnConfirmPurchase).setOnClickListener {
            processCheckout()
        }

        etAmountPaid.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { calculateChange() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun calculateChange() {
        val amountPaid = etAmountPaid.text.toString().toDoubleOrNull() ?: 0.0
        val change = amountPaid - cartTotal
        if (change >= 0) {
            tvChange.text = getString(R.string.change_label, change)
            tvChange.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            tvChange.text = getString(R.string.insufficient_label, -change)
            tvChange.setTextColor(Color.parseColor("#C62828"))
        }
    }

    private fun showProductPreview(code: String) {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT name, price, category, current_stock, actual_stock, image FROM products WHERE barcode = ?", arrayOf(code))

        if (cursor.moveToFirst()) {
            lastScannedCode = code
            val name = cursor.getString(0)
            val price = cursor.getDouble(1)
            val cat = cursor.getString(2)
            val current = cursor.getInt(3)
            val actual = cursor.getInt(4)
            val img = cursor.getString(5)

            tvPreviewName.text = name
            tvPreviewCategory.text = getString(R.string.category_label, cat)
            tvPreviewPrice.text = String.format(Locale.getDefault(), "₱%.2f", price)
            tvPreviewStock.text = getString(R.string.stock_label, current, actual)
            
            if (!img.isNullOrEmpty()) {
                val bytes = Base64.decode(img, Base64.DEFAULT)
                ivPreviewImage.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            } else ivPreviewImage.setImageResource(android.R.drawable.ic_menu_gallery)
            
            productPreviewContainer.visibility = View.VISIBLE
        } else Toast.makeText(this, "Product not found!", Toast.LENGTH_SHORT).show()
        cursor.close()
    }

    private fun addProductToCart(barcode: String) {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT name, price, category, current_stock FROM products WHERE barcode = ?", arrayOf(barcode))

        if (cursor.moveToFirst()) {
            val name = cursor.getString(0)
            val price = cursor.getDouble(1)
            val category = cursor.getString(2)
            val stock = cursor.getInt(3)

            if (stock <= 0) {
                Toast.makeText(this, "Item out of stock!", Toast.LENGTH_SHORT).show()
            } else {
                val existingItem = cart.find { it.barcode == barcode }
                if (existingItem != null) {
                    if (existingItem.quantity < stock) {
                        existingItem.quantity++
                    } else Toast.makeText(this, "No more stock!", Toast.LENGTH_SHORT).show()
                } else {
                    cart.add(CartItem(barcode, name, price, 1, stock, category))
                }
                updateCartUI()
            }
        }
        cursor.close()
    }

    private fun updateCartUI() {
        cartItemsList.removeAllViews()
        cartTotal = 0.0
        
        if (cart.isEmpty()) {
            cartContainer.visibility = View.GONE
            return
        }
        
        cartContainer.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(this)
        
        for (item in cart) {
            val itemView = inflater.inflate(R.layout.item_cart, cartItemsList, false)
            val txtName = itemView.findViewById<TextView>(R.id.txtCartName)
            val txtPrice = itemView.findViewById<TextView>(R.id.txtCartPrice)
            val txtQty = itemView.findViewById<TextView>(R.id.txtCartQty)
            val btnAdd = itemView.findViewById<ImageButton>(R.id.btnAddQty)
            val btnRemove = itemView.findViewById<ImageButton>(R.id.btnRemoveQty)
            val btnDelete = itemView.findViewById<ImageButton>(R.id.btnDeleteCartItem)
            
            txtName.text = item.name
            txtPrice.text = String.format(Locale.getDefault(), "₱%.2f", item.price * item.quantity)
            txtQty.text = item.quantity.toString()
            
            btnAdd.setOnClickListener {
                if (item.quantity < item.maxStock) {
                    item.quantity++
                    updateCartUI()
                } else Toast.makeText(this, "Max stock!", Toast.LENGTH_SHORT).show()
            }
            
            btnRemove.setOnClickListener {
                if (item.quantity > 1) {
                    item.quantity--
                    updateCartUI()
                }
            }
            
            btnDelete.setOnClickListener {
                cart.remove(item)
                updateCartUI()
            }
            
            cartItemsList.addView(itemView)
            cartTotal += item.price * item.quantity
        }
        
        tvCartTotal.text = String.format(Locale.getDefault(), "Total: ₱%.2f", cartTotal)
        calculateChange()
    }

    private fun processCheckout() {
        if (cart.isEmpty()) return
        val amountPaid = etAmountPaid.text.toString().toDoubleOrNull() ?: 0.0
        if (amountPaid < cartTotal) {
            Toast.makeText(this, "Insufficient payment!", Toast.LENGTH_SHORT).show()
            return
        }

        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val transactionId = "TXN" + System.currentTimeMillis()
            for (item in cart) {
                db.execSQL("UPDATE products SET current_stock = current_stock - ? WHERE barcode = ?", arrayOf(item.quantity, item.barcode))
                
                val values = ContentValues().apply {
                    put("transaction_id", transactionId)
                    put("product_name", item.name)
                    put("price", item.price)
                    put("category", item.category)
                    put("quantity", item.quantity)
                }
                db.insert("history", null, values)
            }
            db.setTransactionSuccessful()
            Toast.makeText(this, "Checkout successful!", Toast.LENGTH_SHORT).show()
            cart.clear()
            etAmountPaid.text.clear()
            updateCartUI()
            updateStatsDisplay()
        } catch (e: Exception) { Toast.makeText(this, "Checkout failed!", Toast.LENGTH_SHORT).show() }
        finally { db.endTransaction() }
    }

    private fun exportDatabase() {
        try {
            val dbFile = getDatabasePath("CashierDB")
            if (dbFile.exists()) {
                val backupFile = File(externalCacheDir, "CashierDB_Backup.db")
                FileInputStream(dbFile).use { input ->
                    FileOutputStream(backupFile).use { output ->
                        input.copyTo(output)
                    }
                }
                val uri = FileProvider.getUriForFile(this, "$packageName.provider", backupFile)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Export Backup"))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importDatabase(uri: Uri) {
        try {
            val dbFile = getDatabasePath("CashierDB")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dbFile).use { output ->
                    input.copyTo(output)
                }
            }
            Toast.makeText(this, "Import successful! Restarting...", Toast.LENGTH_SHORT).show()
            finish()
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatsDisplay()
    }

    private fun updateStatsDisplay() {
        val db = dbHelper.readableDatabase
        // Total sold = Sum of (actual_stock - current_stock) IS WRONG if we only want to track sales.
        // It should be the total count of items in the HISTORY table.
        val cursor = db.rawQuery("SELECT COUNT(*), SUM(price * quantity) FROM history", null)
        if (cursor.moveToFirst()) {
            tvTotalSold.text = cursor.getInt(0).toString()
            tvTotalSales.text = String.format(Locale.getDefault(), "₱%.2f", cursor.getDouble(1))
        }
        cursor.close()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return

        if (requestCode == PICK_QR_REQUEST) {
            data?.data?.let { uri ->
                try {
                    val bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
                    val code = scanQRFromBitmap(bitmap)
                    if (code != null) showProductPreview(code)
                    else Toast.makeText(this, "No QR found!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        IntentIntegrator.parseActivityResult(requestCode, resultCode, data)?.contents?.let {
            showProductPreview(it)
        }
    }

    private fun scanQRFromBitmap(bitmap: Bitmap): String? {
        val intArray = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(intArray, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, intArray)))
        return try { MultiFormatReader().decode(binaryBitmap).text } catch (e: Exception) { null }
    }
}
