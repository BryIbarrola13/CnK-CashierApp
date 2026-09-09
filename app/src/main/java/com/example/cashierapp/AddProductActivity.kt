package com.example.cashierapp

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class AddProductActivity : AppCompatActivity() {

    private var selectedImageBase64: String? = null
    private var currentQRBitmap: Bitmap? = null
    private var currentProductName: String? = null
    private var photoFile: File? = null
    private val categories = arrayOf("Crochet", "Fuzzy Wire", "Beads", "General")

    companion object {
        private const val PICK_IMAGE_REQUEST = 1
        private const val CAMERA_REQUEST = 3
    }

    private lateinit var dbHelper: ProductDatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_product)

        dbHelper = ProductDatabaseHelper(this)

        val etName = findViewById<EditText>(R.id.etName)
        val etOwnerName = findViewById<EditText>(R.id.etOwnerName)
        val etPrice = findViewById<EditText>(R.id.etPrice)
        val etQuantity = findViewById<EditText>(R.id.etQuantity)
        val btnAdd = findViewById<Button>(R.id.btnAdd)
        val imagePreview = findViewById<ImageView>(R.id.imagePreview)
        val ivQRCode = findViewById<ImageView>(R.id.ivQRCode)
        val qrContainer = findViewById<View>(R.id.qrContainer)
        val spinnerCategory = findViewById<Spinner>(R.id.spinnerCategory)
        val btnCamera = findViewById<Button>(R.id.btnCamera)
        val btnGallery = findViewById<Button>(R.id.btnGallery)

        ArrayAdapter(this, android.R.layout.simple_spinner_item, categories).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerCategory.adapter = this
        }

        btnAdd.setOnClickListener {
            val name = etName.text.toString().trim()
            val owner = etOwnerName.text.toString().trim()
            val price = etPrice.text.toString().toDoubleOrNull()
            val quantity = etQuantity.text.toString().toIntOrNull()
            val category = spinnerCategory.selectedItem.toString()

            if (name.isEmpty() || price == null || quantity == null || owner.isEmpty()) {
                Toast.makeText(this, "Please fill all fields!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val db = dbHelper.writableDatabase
            val cursor = db.rawQuery("SELECT id FROM products WHERE name = ?", arrayOf(name))
            if (cursor.moveToFirst()) {
                Toast.makeText(this, "Product already exists!", Toast.LENGTH_SHORT).show()
                cursor.close()
                return@setOnClickListener
            }
            cursor.close()

            try {
                val barcode = "P" + System.currentTimeMillis()
                val values = ContentValues().apply {
                    put("name", name)
                    put("owner_name", owner)
                    put("price", price)
                    put("actual_stock", quantity)
                    put("current_stock", quantity)
                    put("barcode", barcode)
                    put("image", selectedImageBase64)
                    put("category", category)
                }

                db.insertOrThrow("products", null, values)
                Toast.makeText(this, "Product Added!", Toast.LENGTH_SHORT).show()

                currentQRBitmap = generateQRCode(barcode)
                currentProductName = name
                ivQRCode.setImageBitmap(currentQRBitmap)
                qrContainer.visibility = View.VISIBLE

                etName.text.clear()
                etOwnerName.text.clear()
                etPrice.text.clear()
                etQuantity.text.clear()
                selectedImageBase64 = null
                imagePreview.setImageResource(android.R.drawable.ic_menu_gallery)

            } catch (e: Exception) {
                Toast.makeText(this, "Failed to add product!", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnSaveQR).setOnClickListener {
            currentQRBitmap?.let { saveImageToGallery(it, currentProductName ?: "QR") }
        }

        btnCamera.setOnClickListener {
            openCamera()
        }

        btnGallery.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI), PICK_IMAGE_REQUEST)
        }
    }

    private fun openCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        photoFile = File(externalCacheDir, "temp_product_image.jpg")
        val photoUri = FileProvider.getUriForFile(this, "$packageName.provider", photoFile!!)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        startActivityForResult(intent, CAMERA_REQUEST)
    }

    private fun generateQRCode(text: String): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 500, 500)
        val bitmap = Bitmap.createBitmap(500, 500, Bitmap.Config.RGB_565)
        for (x in 0 until 500) {
            for (y in 0 until 500) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun saveImageToGallery(bitmap: Bitmap, filename: String) {
        try {
            val fos: OutputStream?
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$filename.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "CashierApp")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                fos = contentResolver.openOutputStream(uri!!)
            } else {
                val image = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "$filename.jpg")
                fos = FileOutputStream(image)
            }
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos!!)
            fos.close()
            Toast.makeText(this, "QR Saved!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Save failed!", Toast.LENGTH_SHORT).show() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                PICK_IMAGE_REQUEST -> {
                    data?.data?.let { uri ->
                        processAndSetImage(uri)
                    }
                }
                CAMERA_REQUEST -> {
                    photoFile?.let { file ->
                        processAndSetImage(Uri.fromFile(file))
                    }
                }
            }
        }
    }

    private fun processAndSetImage(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = getResizedBitmap(BitmapFactory.decodeStream(inputStream), 500)
            findViewById<ImageView>(R.id.imagePreview).setImageBitmap(bitmap)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
            selectedImageBase64 = Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT)
        } catch (e: Exception) {
            Toast.makeText(this, "Error processing image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getResizedBitmap(image: Bitmap, maxSize: Int): Bitmap {
        var width = image.width
        var height = image.height
        val ratio = width.toFloat() / height.toFloat()
        if (ratio > 1) { width = maxSize; height = (maxSize / ratio).toInt() }
        else { height = maxSize; width = (maxSize * ratio).toInt() }
        return Bitmap.createScaledBitmap(image, width, height, true)
    }
}