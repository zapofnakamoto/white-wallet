package com.example.acmecash.code

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import whitewallet.brand.ui.BrandScreen
import whitewallet.brand.ui.BrandScreenEntry

/** A page of Acme's own, under Settings. */
class AboutAcme : BrandScreen {
    override val id = "about-acme"
    override val title = R.string.about_acme_title
    override val entry = BrandScreenEntry.Settings

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content(onBack: () -> Unit) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_acme_back), stringResource(R.string.about_acme_back)) }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).padding(24.dp)) {
                Text(stringResource(R.string.about_acme_body), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
