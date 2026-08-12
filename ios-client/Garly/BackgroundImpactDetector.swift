import Foundation

/**
 Il rilevatore che gira quando la pagina non puo' girare.

 Su iPhone chi riconosce uno scuotimento e' sempre stata la pagina: il lato
 nativo raccoglieva i campioni e glieli passava. Funziona finche' l'app e'
 davanti. Quando finisce in secondo piano iOS congela il motore della pagina, i
 campioni si accumulano in coda e nessuno li guarda; alla riapertura la coda si
 smaltisce tutta insieme e l'allarme arriva venti minuti dopo, quando non serve
 piu' a niente. E' stato visto su un telefono vero, camminando: lo scuotimento
 e' stato riconosciuto solo riaprendo Garly.

 Quindi questo. Stessi conti e stesse soglie del rilevatore della pagina in
 modalita' "everyday", perche' due rilevatori che rispondono diversamente allo
 stesso gesto sono peggio di uno solo.

 Due scelte deliberate, e valgono piu' delle soglie:

 - **Gira solo quando la pagina non puo'.** Con l'app davanti decide la pagina,
   che ha il rilevatore completo, la taratura per la borsa e la corsa. Qui non
   si duplica quel lavoro: si copre il buco. Un solo padrone alla volta, quindi
   non esiste il caso di due allarmi per lo stesso scuotimento.

 - **Il passo si zittisce.** Camminare con il telefono in tasca e' esattamente
   la situazione in cui questo rilevatore e' l'unico sveglio. Il passo e'
   regolare, un'aggressione no: se gli ultimi picchi sono distanziati in modo
   uniforme fra 180 e 650 ms, non si conta niente. Un urto forte scavalca
   comunque questa regola, perche' una caduta durante una camminata resta una
   caduta.
 */
final class BackgroundImpactDetector {

    /// Le soglie di "everyday" nella pagina, senza cambiarne una.
    private enum T {
        static let shakePeak = 11.0
        static let shakeJerk = 10.0
        static let peaksNeeded = 4
        static let peakGap: TimeInterval = 0.200
        static let resetMs: TimeInterval = 1.750
        static let energyTrigger = 5.0
        static let severeMag = 42.0
        static let severeRaw = 24.0
        static let severeJerk = 28.0
        static let severeRawJerk = 22.0
        static let gyro = 250.0
        static let gyroJerk = 160.0
        static let cooldown: TimeInterval = 10
    }

    /* Dopo ogni passaggio in secondo piano si lascia passare qualche istante:
       e' li' che il telefono viene appoggiato o infilato in tasca, e quel colpo
       e' forte quanto una caduta.

       Erano tre secondi, e non bastavano. Sul telefono di prova un allarme e'
       partito alle 06:42:00, esattamente tre secondi dopo il passaggio in
       secondo piano: il gesto di mettere via il telefono era ancora in corso e
       ha fatto scattare tutto nell'istante in cui la finestra e' scaduta.
       Otto secondi coprono il gesto per intero. Il prezzo e' che un evento
       nei primi otto secondi dopo che si e' messo via il telefono non viene
       visto - ma quello e' il momento in cui la persona ce l'ha ancora in mano,
       e la pagina davanti sta guardando lo stesso. */
    /* Tre secondi, non otto.

       Gli otto servivano a coprire il gesto di mettere via il telefono, quando
       l'appoggio faceva ancora scattare l'allarme. Adesso non lo fa piu': un
       urto conta solo se preceduto da una caduta libera vera, e appoggiare non
       ne ha mai una. Quindi la finestra lunga non protegge piu' da niente, e
       intanto rendeva sordo il telefono per otto secondi dopo ogni chiusura
       dell'app - cioe' proprio quando qualcuno lo mette in tasca e comincia a
       camminare. */
    private static let settleWindow: TimeInterval = 3

    /* Cosa separa uno scuotimento da un appoggio: la **densita'**, non la
       forza, non la rotazione, e nemmeno la durata da sola.

       Tre tentativi e tre smentite dai numeri misurati sul telefono:
       - la forza no: appoggiarlo fa 6,4 g, piu' di una caduta;
       - la rotazione no: appoggiarlo fa 933 gradi al secondo, piu' di ogni
         scuotimento misurato (335, 489, 347, 518);
       - la durata da sola no: quattro colpi spalmati su **tre secondi** hanno
         fatto scattare l'allarme, ed erano il telefono appoggiato e poi
         toccato.

       Uno scuotimento e' una raffica fitta: quattro colpi **forti** dentro un
       secondo e due. Un telefono appoggiato ne fa uno, e se poi lo si sposta
       quei colpi sono lenti e lontani. La finestra scorre, quindi conta la
       densita' e non il totale accumulato. */
    private static let shakeWindow: TimeInterval = 1.2
    private static let shakePeaksInWindow = 3

    /* Distanza minima fra due colpi contati. Erano 200 ms, ereditati dalla
       pagina, e su uno scuotimento vero buttavano via meta' dei colpi: si
       scuote a cinque o sei volte al secondo, cioe' un colpo ogni 160-200 ms,
       e con quel filtro ne passava uno su due. Il conteggio non arrivava mai a
       tre. 120 ms li lascia passare tutti e resta comunque largo per un
       appoggio, che di colpi ne fa uno. */
    private static let shakePeakGap: TimeInterval = 0.120

    /* Cosa distingue un telefono appoggiato da una caduta.

       Non l'intensita': misurato sul telefono, appoggiarlo su un tavolo duro
       produce 6,4 g e 933 gradi al secondo - piu' violento di quasi qualunque
       caduta di una persona, perche' il tavolo e' rigido e il colpo dura
       millesimi. Alzare la soglia dell'urto vorrebbe dire smettere di
       riconoscere le cadute, che e' il contrario di quello che serve.

       La differenza sta in cosa c'e' prima e dopo:

       - **Prima.** Qualcosa che cade e' in caduta libera, e in caduta libera
         l'accelerometro legge quasi zero. Una mano che appoggia il telefono lo
         accompagna: la lettura non scende mai. Questa e' la firma pulita.
       - **Dopo.** Un telefono appoggiato si ferma e resta fermo. Attorno a una
         persona caduta il telefono continua a muoversi.

       Quindi un urto forte non fa piu' scattare niente da solo: o e' preceduto
       da una caduta libera, oppure aspetta due secondi e chiede che il
       movimento continui. Se non succede ne' l'uno ne' l'altro, era un
       appoggio. */
    /* 3,0 m/s^2, non 5,2.

       5,2 e' mezzo g, e mezzo g lo si ottiene abbassando la mano di scatto: il
       telefono non cade, pesa solo di meno per un istante. Misurato sul
       telefono, appoggiarlo passava proprio di qui.

       Una caduta vera legge quasi zero. E dura: da venti centimetri sono circa
       due decimi di secondo, cioe' una dozzina di campioni di fila. Un
       abbassamento di mano ne produce due o tre. Quindi non basta scendere
       sotto la soglia: bisogna restarci. */
    private static let freeFallCeiling = 3.0
    private static let freeFallSamplesNeeded = 6      // ~100 ms a 60 Hz
    private static let freeFallWindow: TimeInterval = 1.5

    private var previousMagnitude: Double?
    private var previousRawMagnitude = 0.0
    private var previousRotationMagnitude = 0.0
    private var peakCount = 0
    private var lastPeakAt = Date.distantPast
    private var energy = 0.0
    private var recentPeaks: [Date] = []
    private var lastFireAt = Date.distantPast
    private var activeSince = Date.distantPast
    private var lastFreeFallAt = Date.distantPast
    private var freeFallSamples = 0

    /// Si riparte puliti ogni volta che questo rilevatore prende il turno.
    func begin(at now: Date = Date()) {
        previousMagnitude = nil
        previousRawMagnitude = 0
        previousRotationMagnitude = 0
        peakCount = 0
        lastPeakAt = .distantPast
        energy = 0
        recentPeaks = []
        lastFreeFallAt = .distantPast
        freeFallSamples = 0
        activeSince = now
    }

    /// - Returns: `true` se questo campione chiude un riconoscimento.
    func consider(x: Double, y: Double, z: Double,
                  alpha: Double, beta: Double, gamma: Double,
                  now: Date = Date()) -> Bool {

        let magnitude = (x * x + y * y + z * z).squareRoot()
        let rawMagnitude = abs(magnitude - 9.81)
        let rotationMagnitude = (alpha * alpha + beta * beta + gamma * gamma).squareRoot()

        defer {
            previousMagnitude = magnitude
            previousRawMagnitude = rawMagnitude
            previousRotationMagnitude = rotationMagnitude
        }

        // Il primo campione non ha un precedente: una differenza contro zero
        // sarebbe un salto enorme e verrebbe letta come un urto.
        guard let lastMagnitude = previousMagnitude else { return false }
        guard now.timeIntervalSince(activeSince) >= Self.settleWindow else { return false }
        guard now.timeIntervalSince(lastFireAt) >= T.cooldown else { return false }

        let jerk = abs(magnitude - lastMagnitude)
        let rawJerk = abs(rawMagnitude - previousRawMagnitude)
        let rotationJerk = abs(rotationMagnitude - previousRotationMagnitude)

        let gyroSignal = rotationMagnitude >= T.gyro || rotationJerk >= T.gyroJerk
        let severeImpact = magnitude >= T.severeMag
            || rawMagnitude >= T.severeRaw
            || jerk >= T.severeJerk
            || rawJerk >= T.severeRawJerk
            || (gyroSignal && rawMagnitude >= 9)

        let strongPeak = rawMagnitude >= T.shakePeak || rawJerk >= T.shakeJerk || jerk >= T.shakeJerk
        let shakeSignal = strongPeak || (gyroSignal && rawMagnitude >= max(3.5, T.shakePeak * 0.75))

        /* Solo i colpi veri entrano nel conteggio: ampiezza sopra soglia, non
           un semplice strappo. Uno strappo lo produce anche un tocco. */
        if rawMagnitude >= T.shakePeak, now.timeIntervalSince(lastPeakAt) >= Self.shakePeakGap {
            lastPeakAt = now
            recentPeaks.append(now)
            if recentPeaks.count > 12 { recentPeaks.removeFirst(recentPeaks.count - 12) }
        }
        // La finestra scorre: quello che e' vecchio esce da solo.
        recentPeaks.removeAll { now.timeIntervalSince($0) > Self.shakeWindow }
        peakCount = recentPeaks.count
        energy = Double(peakCount)

        /* La caduta libera, contata e non solo sfiorata: serve una dozzina di
           campioni di fila sotto soglia, non un tocco. Va valutata prima
           dell'urto, perche' lo precede. */
        if magnitude <= Self.freeFallCeiling {
            freeFallSamples += 1
            if freeFallSamples >= Self.freeFallSamplesNeeded { lastFreeFallAt = now }
        } else {
            freeFallSamples = 0
        }

        /* Un urto forte fa scattare l'allarme **solo** se prima c'e' stata una
           caduta vera. Niente altre strade.

           C'era anche "urto seguito da movimento", ed e' stata tolta: appoggiare
           il telefono e poi toccarlo un istante dopo la percorreva tutta. Un
           falso allarme al giorno svuota l'app piu' in fretta di qualunque
           funzione mancante, perche' la prima cosa che si spegne dopo il terzo
           e' la Protezione. */
        if severeImpact {
            guard now.timeIntervalSince(lastFreeFallAt) <= Self.freeFallWindow else { return false }
            fire(at: now)
            return true
        }

        if looksLikeWalking(now: now) {
            peakCount = max(0, peakCount - 1)
            energy = max(0, energy - 1.2)
            return false
        }

        // Quattro colpi forti dentro un secondo e due: e' una raffica.
        if peakCount >= Self.shakePeaksInWindow {
            fire(at: now)
            return true
        }
        return false
    }

    private func fire(at now: Date) {
        lastFireAt = now
        peakCount = 0
        energy = 0
        recentPeaks = []
        freeFallSamples = 0
        lastFreeFallAt = .distantPast
    }

    /// Lo stesso giudizio di `looksLikeJoggingCadence` nella pagina: picchi
    /// distanziati in modo regolare fra 180 e 650 ms sono un'andatura, dalla
    /// camminata svelta alla corsa.
    private func looksLikeWalking(now: Date) -> Bool {
        let recent = recentPeaks.filter { now.timeIntervalSince($0) < 3.2 }
        guard recent.count >= 4 else { return false }
        var gaps: [TimeInterval] = []
        for i in 1..<recent.count { gaps.append(recent[i].timeIntervalSince(recent[i - 1])) }
        let average = gaps.reduce(0, +) / Double(gaps.count)
        guard average >= 0.180, average <= 0.650 else { return false }
        let variance = gaps.reduce(0) { $0 + ($1 - average) * ($1 - average) } / Double(gaps.count)
        return variance.squareRoot() < 0.160
    }
}
