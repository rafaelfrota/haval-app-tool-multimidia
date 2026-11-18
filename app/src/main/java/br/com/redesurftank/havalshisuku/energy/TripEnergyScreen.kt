package br.com.redesurftank.havalshisuku.energy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.redesurftank.havalshisuku.models.SharedPreferencesKeys
import br.com.redesurftank.havalshisuku.ui.components.AppColors
import br.com.redesurftank.havalshisuku.ui.components.ImpTokens
import br.com.redesurftank.havalshisuku.ui.components.StyledCard
import br.com.redesurftank.havalshisuku.ui.theme.Michroma
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val PT_BR = Locale("pt", "BR")

private fun brl(v: Double): String = String.format(PT_BR, "R$ %.2f", v)

private fun num(v: Double, decimals: Int = 1): String = String.format(PT_BR, "%.${decimals}f", v)

@Composable
fun TripEnergyScreen(onBackToFeatures: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            tick++
        }
    }
    @Suppress("UNUSED_VARIABLE") val refresh = tick
    val prices = TripEnergyTracker.prices()
    val current = TripEnergyTracker.current
    val history = remember(tick / 15) { TripEnergyTracker.history() }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .background(AppColors.Background)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(46.dp).clickable(onClick = onBackToFeatures),
                shape = RoundedCornerShape(14.dp),
                color = ImpTokens.Container,
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = ImpTokens.Accent, modifier = Modifier.padding(11.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("ENERGIA DA VIAGEM", color = AppColors.TextPrimary, fontFamily = Michroma, fontSize = 22.sp, letterSpacing = 1.sp)
                Text("Bateria, gasolina e quanto custou, viagem por viagem.", color = AppColors.TextSecondary, fontSize = 16.sp)
            }
        }

        if (current == null) {
            StyledCard { Text("Esperando as primeiras leituras do carro…", color = AppColors.TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(20.dp)) }
        } else {
            TripCard("Viagem atual", current, prices, highlight = true)
        }

        PricesCard(prices)

        if (history.isNotEmpty()) {
            Text("Últimas viagens", color = AppColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            history.forEach { TripCard(null, it, prices, highlight = false) }
        }
    }
}

@Composable
private fun TripCard(title: String?, trip: TripEnergy, prices: TripEnergy.Prices, highlight: Boolean) {
    val s = trip.summary(prices)
    val started = SimpleDateFormat("dd/MM HH:mm", PT_BR).format(Date(trip.startedAtMs))
    StyledCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title ?: started,
                    color = AppColors.TextPrimary,
                    fontSize = if (highlight) 22.sp else 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(brl(s.totalCost), color = ImpTokens.Accent, fontSize = if (highlight) 26.sp else 20.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "${num(s.distanceKm)} km · ${s.durationMin} min" + (if (title != null) " · desde $started" else ""),
                color = AppColors.TextSecondary,
                fontSize = 15.sp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Bateria gasta", "${num(s.usedKwh, 2)} kWh", Modifier.weight(1f))
                Stat("Regenerada", "${num(s.regenKwh, 2)} kWh", Modifier.weight(1f))
                Stat("No elétrico", s.evShare?.let { "${(it * 100).toInt()}%" } ?: "–", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Gasolina", s.liters?.let { "${num(it, 2)} L" } ?: "–", Modifier.weight(1f))
                Stat("Luz", brl(s.energyCost), Modifier.weight(1f))
                Stat("Gasolina R$", s.fuelCost?.let { brl(it) } ?: "–", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Por km", s.costPerKm?.let { brl(it) } ?: "–", Modifier.weight(1f))
                Stat("Média elétrica", s.kwhPer100Km?.let { "${num(it)} kWh/100km" } ?: "–", Modifier.weight(1f))
                Stat("Média gasolina", trip.avgL100?.let { "${num(it)} L/100km" } ?: "–", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = ImpTokens.TextMuted, fontSize = 13.sp)
        Text(value, color = AppColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PricesCard(prices: TripEnergy.Prices) {
    StyledCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Preços", color = AppColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "A luz vem da sua conta do Saint Remy (março/2026: R$ 1.024,20 por 1.120 kWh, R$ 0,91 o kWh). " +
                    "A perda na recarga soma o que se perde entre a tomada e a bateria.",
                color = AppColors.TextSecondary,
                fontSize = 14.sp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PriceField("R$ por kWh", prices.tariff, SharedPreferencesKeys.TRIP_ENERGY_TARIFF, Modifier.weight(1f))
                PriceField("R$ por litro", prices.fuelPrice, SharedPreferencesKeys.TRIP_ENERGY_FUEL_PRICE, Modifier.weight(1f))
                PriceField("Perda na recarga %", prices.chargeLoss * 100, SharedPreferencesKeys.TRIP_ENERGY_CHARGE_LOSS, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PriceField(label: String, value: Double, key: SharedPreferencesKeys, modifier: Modifier) {
    var text by remember { mutableStateOf(num(value, 2)) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            if (it.replace(',', '.').toDoubleOrNull() != null) TripEnergyTracker.savePrice(key, it)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}
