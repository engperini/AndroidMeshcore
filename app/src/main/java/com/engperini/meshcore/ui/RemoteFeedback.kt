package com.engperini.meshcore.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.engperini.meshcore.core.RemoteState
import com.engperini.meshcore.core.tr
import java.util.Locale

fun secondsLabel(ms: Long): String = "%.1f s".format(Locale.US, ms / 1000.0)

/**
 * One line telling where a request to a remote node stands: waiting (and how it was sent), answered (and
 * how long it took) or no answer (with [noAnswerHint]). Shows [idle] when nothing was sent yet.
 */
@Composable
fun RemoteFeedback(st: RemoteState?, noAnswerHint: String, idle: String? = null) {
    when {
        st == null -> idle?.let { Muted(it, size = 12) }
        st.waiting -> {
            val via = if (st.flood) {
                tr("sent by flood, route unknown", "enviado por flood, rota desconhecida")
            } else {
                tr("sent direct", "enviado direto")
            }
            Text(
                tr("Waiting for the answer… ($via, up to ${st.timeoutMs / 1000} s)", "Aguardando resposta… ($via, até ${st.timeoutMs / 1000} s)"),
                color = Mesh.AMBER, fontSize = 12.sp,
            )
        }
        st.timedOut -> Text(
            tr("No answer in ${st.timeoutMs / 1000} s. ", "Sem resposta em ${st.timeoutMs / 1000} s. ") + noAnswerHint,
            color = Mesh.RED, fontSize = 12.sp,
        )
        st.answeredMs != null -> Text(
            tr("Answered in ${secondsLabel(st.answeredMs)}", "Respondeu em ${secondsLabel(st.answeredMs)}"),
            color = Mesh.GREEN, fontSize = 12.sp,
        )
    }
}
