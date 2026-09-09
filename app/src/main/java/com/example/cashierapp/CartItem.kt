package com.example.cashierapp

data class CartItem(
    val barcode: String,
    val name: String,
    val price: Double,
    var quantity: Int,
    val maxStock: Int,
    val category: String
)
