package com.example.cashierapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ProductDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, "CashierDB", null, 6) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE products (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT UNIQUE,
                price REAL,
                actual_stock INTEGER,
                current_stock INTEGER,
                barcode TEXT,
                image TEXT,
                category TEXT,
                owner_name TEXT
            );
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                transaction_id TEXT,
                product_name TEXT,
                price REAL,
                category TEXT,
                quantity INTEGER DEFAULT 1,
                sale_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE products ADD COLUMN category TEXT DEFAULT 'General'")
        }
        if (oldVersion < 4) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product_name TEXT,
                    price REAL,
                    category TEXT,
                    sale_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                );
            """.trimIndent())
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE history ADD COLUMN transaction_id TEXT")
            db.execSQL("ALTER TABLE history ADD COLUMN quantity INTEGER DEFAULT 1")
            db.execSQL("UPDATE history SET transaction_id = 'T' || id WHERE transaction_id IS NULL")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE products ADD COLUMN owner_name TEXT DEFAULT 'Unknown'")
        }
    }
}