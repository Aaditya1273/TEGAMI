package com.tegami

import com.funkatronics.encoders.Base58
import com.tegami.Fixtures.config
import com.tegami.domain.Letter
import com.tegami.domain.LetterRules
import com.tegami.domain.Money
import com.tegami.proof.DraftResult
import com.tegami.proof.Ed25519
import com.tegami.proof.IncomeRules
import com.tegami.proof.IncomeStatement
import com.tegami.proof.SignedStatement
import com.tegami.proof.StatementBuilder
import com.tegami.proof.StatementCodec
import com.tegami.proof.StatementVerifier
import com.tegami.solana.TransferParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate

class StatementTest {
    // A throwaway key stands in for the recipient wallet; fixtures are rewritten to pay its address.
    private val key = Ed25519PrivateKeyParameters(SecureRandom())
    private val me = Base58.encodeToString(key.generatePublicKey().encoded)
    private val day = LocalDate.parse("2026-10-12")
    private val now = Instant.parse("2026-10-12T12:00:00Z")
    private val usd = { s: String -> Money.parse(s, 6, "tUSD") }

    private val txs: Map<String, JsonObject> = listOf(Fixtures.SEALED, Fixtures.AGENCY_SEALED, Fixtures.NO_MEMO,
        Fixtures.STRANGER, Fixtures.INJECTION, Fixtures.CONFIDENTIAL).associate { (sig, name) -> sig to Fixtures.tx(name, me) }

    private val letters: List<Letter> = txs.map { (sig, tx) ->
        LetterRules.toLetter(TransferParser.parse(sig, tx, me, Fixtures.MINT)!!, LetterRules.settlement(false, "finalized"), config)
    }

    private fun sign(s: IncomeStatement): SignedStatement {
        val bytes = s.message().toByteArray()
        val sig = Ed25519Signer().run { init(true, key); update(bytes, 0, bytes.size); generateSignature() }
        return SignedStatement(s, Base58.encodeToString(sig))
    }

    private fun draft(threshold: String, verifier: String = "Landlord") =
        StatementBuilder.draft(config, me, letters, usd(threshold), day.minusDays(29), day, verifier, day, now, nonce = "n1")

    private fun verifier(at: Instant = now) = StatementVerifier(config, { sig -> txs[sig] }, { at })

    @Test fun availableIncomeCountsOnlyRegisteredFinalizedReadable() {
        // 1250 + 860.5 + 400 + 75 (registered agency, injection note) = 2585.5; stranger 340 and confidential excluded.
        assertEquals(usd("2585.5"), IncomeRules.availableTotal(letters, day.minusDays(29), day, 6, "tUSD"))
    }

    @Test fun disclosesOnlyWhatTheClaimNeeds() {
        val d = (draft("2000") as DraftResult.Ready).draft
        assertEquals(listOf(Fixtures.SEALED.first, Fixtures.AGENCY_SEALED.first), d.statement.evidence)
        assertEquals(usd("2110.5"), d.selection.disclosedTotal)
        assertEquals(4, d.selection.qualifyingCount)
    }

    @Test fun builderRejectsBadInput() {
        assertTrue(draft("0") is DraftResult.Invalid)
        assertTrue(draft("999999") is DraftResult.Invalid)
        assertTrue(draft("100", verifier = " ") is DraftResult.Invalid)
        assertTrue(draft("100", verifier = "x".repeat(61)) is DraftResult.Invalid)
        assertTrue(StatementBuilder.draft(config, me, letters, usd("1"), day, day.plusDays(1), "L", day, now) is DraftResult.Invalid)
        assertTrue(StatementBuilder.draft(config, me, letters, usd("1"), day.minusDays(400), day, "L", day, now) is DraftResult.Invalid)
    }

    @Test fun signedStatementVerifiesAgainstChainData() = runBlocking {
        val signed = sign((draft("2000") as DraftResult.Ready).draft.statement)
        val decoded = StatementCodec.decode("Here you go: ${StatementCodec.link(signed)} thanks").getOrThrow()
        val report = verifier().verify(decoded)
        assertTrue(report.checks.joinToString { "${it.title}: ${it.detail}" }, report.verified)
        assertEquals(usd("2110.5"), report.total)
        assertEquals(2, report.disclosed.size)
    }

    @Test fun tamperedClaimFailsSignature() = runBlocking {
        val signed = sign((draft("2000") as DraftResult.Ready).draft.statement)
        val forged = signed.copy(statement = signed.statement.copy(thresholdUnits = 9_000_000_000))
        val report = verifier().verify(forged)
        assertFalse(report.verified)
        assertEquals("Wallet signature", report.checks.last().title)
    }

    @Test fun expiredStatementFails() = runBlocking {
        val signed = sign((draft("2000") as DraftResult.Ready).draft.statement)
        val report = verifier(now.plusSeconds(31L * 86_400)).verify(signed)
        assertFalse(report.verified)
        assertEquals("Validity period", report.checks.last().title)
    }

    @Test fun unregisteredOrEncryptedEvidenceFails() = runBlocking {
        val base = (draft("100") as DraftResult.Ready).draft.statement
        for (sig in listOf(Fixtures.STRANGER.first, Fixtures.CONFIDENTIAL.first, "missing")) {
            val report = verifier().verify(sign(base.copy(evidence = listOf(sig))))
            assertFalse(sig, report.verified)
            assertTrue(report.checks.last().title.startsWith("Payment"))
        }
    }

    @Test fun claimAboveEvidenceFails() = runBlocking {
        val base = (draft("2000") as DraftResult.Ready).draft.statement
        val report = verifier().verify(sign(base.copy(thresholdUnits = 5_000_000_000)))
        assertFalse(report.verified)
        assertEquals("Claim", report.checks.last().title)
    }

    @Test fun someoneElsesPaymentsCantBeClaimed() = runBlocking {
        // Signed by our key, but the evidence pays the original fixture recipient, not us.
        val original = mapOf(Fixtures.SEALED.first to Fixtures.tx("tx_sealed"))
        val s = (draft("1000") as DraftResult.Ready).draft.statement.copy(evidence = listOf(Fixtures.SEALED.first))
        val report = StatementVerifier(config, { original[it] }, { now }).verify(sign(s))
        assertFalse(report.verified)
    }

    @Test fun codecRejectsJunk() {
        assertTrue(StatementCodec.decode("").isFailure)
        assertTrue(StatementCodec.decode("tegami://verify?s=%%%").isFailure)
        assertTrue(StatementCodec.decode("a".repeat(40_000)).isFailure)
        assertTrue(StatementCodec.decode("eyJmb28iOjF9").isFailure) // {"foo":1}
    }

    @Test fun ed25519RejectsWrongKey() {
        val s = sign((draft("2000") as DraftResult.Ready).draft.statement)
        assertTrue(Ed25519.verify(me, s.statement.message(), s.signature))
        assertFalse(Ed25519.verify(Fixtures.STUDIO, s.statement.message(), s.signature))
    }
}
