package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupActionBarWithNavController
import com.example.myapplication.smarthelmet.StreamActivity
import com.example.myapplication.BuildConfig
import com.google.android.material.appbar.MaterialToolbar

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1) 커스텀 툴바를 액션바로 등록
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        // 2) NavController와 액션바 연결
        val nav = supportFragmentManager
            .findFragmentById(R.id.nav_host) as NavHostFragment
        setupActionBarWithNavController(nav.navController)
    }

    override fun onSupportNavigateUp() =
        (supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment)
            .navController.navigateUp()

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (BuildConfig.DEBUG) {
            menuInflater.inflate(R.menu.menu_main_debug, menu)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_dev_stream) {
            startActivity(
                Intent(this, StreamActivity::class.java)
                    .putExtra("PI_IP", "10.42.0.1")
                    .putExtra("ENTRY", "dev_menu")
            )
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
