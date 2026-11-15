package com.glucosescan.app.core

/**
 * The words of [Advisor], in the three languages the user can choose.
 *
 * The patterns are named after what they say; the numbers that go into them are listed in
 * [Words]. Nothing here is a diagnosis, and no pattern may tell the user to change a dose.
 *
 * A Somali and an Arabic speaker should read these two sets of sentences once before the
 * app reaches many people: they were written from the English, not by a clinician.
 */
internal object English : Words() {
    override val titlePeriod = "The period"
    override val titleLine = "In one line"
    override val titleMean = "The average"
    override val titleRange = "Time in range"
    override val titleSteady = "How steady it was"
    override val titleHours = "Hour by hour"
    override val titleParts = "The parts of the day"
    override val titleMorning = "The early morning"
    override val titleLows = "Lows"
    override val titleHighs = "Highs"
    override val titleDays = "Day by day"
    override val titleBefore = "Compared with the days before"
    override val titleGoals = "The goals"
    override val titleDoctor = "What to tell your doctor"
    override val titleQuestions = "Questions to take with you"
    override val titleCare = "The careful words"

    override val noValues = "There are no values in this period yet. Scan the sensor, or switch LibreLinkUp on, and the app will say what it sees."
    override val period = "From %1\$s to %2\$s. That is %3\$s. Values are there for %4\$d %% of that time."
    override val quarters = "The app counted %1\$d quarter hours with values; each one stands for 15 minutes."

    override val steady = "Steady, and inside the target range most of the time. Mean %1\$s, in range %2\$d %% of the time."
    override val inRange = "Mostly inside the target range. Mean %1\$s, in range %2\$d %% of the time."
    override val oftenHigh = "Often high. Mean %1\$s, over %2\$s for %3\$d %% of the time."
    override val lows = "There were lows. Mean %1\$s, under %2\$s for %3\$d %% of the time."
    override val veryLow = "There was a value under %1\$s. That is a very low value: tell your doctor or your diabetes nurse soon."

    override val mean = "Mean glucose: %1\$s."
    override val extremes = "Lowest %1\$s, highest %2\$s. The widest swing was %3\$s."
    override val gmi = "A mean like this is about an HbA1c of %1\$s (estimated, from the values alone). Only a blood test can say it for certain."
    override val gmiTooShort = "Too short to estimate HbA1c: that needs at least 14 days with values on most of the time."

    override val rangeLine = "In range %1\$s to %2\$s: %3\$d %% of the time."
    override val sidesLine = "Under %1\$s: %2\$d %% of the time. Over %3\$s: %4\$d %% of the time."
    override val tightLine = "In the tight range %1\$s to %2\$s: %3\$d %% of the time."

    override val cvLine = "The values move around the mean by %1\$d %% (this is called CV)."
    override val cvSteady = "That is %1\$d %% or less, which is called steady: the values stay near the mean."
    override val cvUnsteady = "That is more than %1\$d %%, which is called unsteady: highs and lows follow each other quickly."

    override val calmestHour = "Calmest hour of the day: %1\$02d:00, mean %2\$s."
    override val highestHour = "Highest hour of the day: %1\$02d:00, mean %2\$s."

    override val partLine = "%1\$s: in range %2\$d %%, under %3\$d %%, over %4\$d %%."
    override val partNight = "Night (00:00 to 06:00)"
    override val partMorning = "Morning (06:00 to 12:00)"
    override val partAfternoon = "Afternoon (12:00 to 18:00)"
    override val partEvening = "Evening (18:00 to 24:00)"

    override val morningRose = "Over %1\$d nights the glucose rose by %2\$s between 2:30 and 5:30 in the morning."
    override val morningFell = "Over %1\$d nights the glucose fell by %2\$s between 2:30 and 5:30 in the morning."
    override val morningDawn = "A rise like this in the early morning is common and has a name: the dawn phenomenon. Mention it to your doctor."
    override val morningNone = "Too few nights have values at both times (3 nights are needed), so nothing is said about the early morning."

    override val lowCount = "%1\$d low %2\$s of 15 minutes or longer (under %3\$s)."
    override val lowLongest = "The longest lasted %1\$s and began at %2\$s."
    override val lowNight = "At night (midnight to 06:00): %1\$d low %2\$s."
    override val lowNightCare = "Lows at night are the most important thing to tell your doctor."
    override val lowNone = "No low of 15 minutes or longer. That is good."

    override val highCount = "%1\$d high %2\$s of 15 minutes or longer (over %3\$s)."
    override val highLongest = "The longest lasted %1\$s and began at %2\$s."
    override val highSpikes = "%1\$d fast %2\$s: a rise of %3\$s or more within one hour. The biggest was around %4\$02d:00."
    override val highNone = "No high of 15 minutes or longer."

    override val dayLine = "%1\$s: in range %2\$d %%, mean %3\$s, %4\$d low, %5\$d high."

    override val beforeLine = "In the %3\$s days before, the mean was %1\$s and in range %2\$d %% of the time."
    override val beforeDown = "Compared with then, the values came down."
    override val beforeUp = "Compared with then, the values went up."
    override val beforeSame = "Compared with then, it stayed about the same."

    override val goalLine = "%1\$s: %2\$s. Goal: %3\$s. %4\$s"
    override val goalInRange = "Time in range"
    override val goalLow = "Time under %1\$s"
    override val goalVeryLow = "Time under %1\$s"
    override val goalHigh = "Time over %1\$s"
    override val goalVeryHigh = "Time over %1\$s"
    override val goalVariation = "Variation (CV)"
    override val goalMet = "Reached."
    override val goalMissed = "Not reached."

    override val doctorNight = "%1\$d low at night. Ask what can be changed so that they stop."
    override val doctorLows = "%1\$d low in %2\$s. Say when they come and what you were doing."
    override val doctorHigh = "Over %1\$s for %2\$d %% of the time, more than the goal of 25 %%."
    override val doctorCv = "The values are unsteady (CV %1\$d %%), more than the goal of 36 %%."
    override val doctorDawn = "The glucose rises in the early morning (the dawn phenomenon)."
    override val doctorGaps = "Only %1\$d %% of the time has values, so the picture has holes in it."
    override val doctorNothing = "Nothing stands out in the numbers. Take this report to your next visit all the same."

    override val question1 = "Are my targets right for me? Mine are %1\$s to %2\$s."
    override val question2 = "What shall I do when the value is under %1\$s?"
    override val question3 = "What shall I do when it stays over %1\$s?"
    override val question4 = "Do my medicines need a change? Only you can decide that, not this app."
    override val question5 = "How often shall I check, and when shall I call you?"

    override val care = "This text is written by fixed rules inside the app, not by a doctor. It describes what the sensor measured. It gives no dose, no medicine and no treatment. Never change insulin or a medicine because of it. This app is not a medical device."

    override fun episodes(count: Int): String = if (count == 1) "episode" else "episodes"
    override fun length(days: Int): String = if (days <= 1) "24 hours" else "$days days"
}

internal object Somali : Words() {
    override val titlePeriod = "Muddada"
    override val titleLine = "Hal jumlad"
    override val titleMean = "Celceliska"
    override val titleRange = "Waqtiga xadka ku jira"
    override val titleSteady = "Sida ay u deggan tahay"
    override val titleHours = "Saacadba saacad"
    override val titleParts = "Qaybaha maalinta"
    override val titleMorning = "Aroortii hore"
    override val titleLows = "Hoos u dhacyada"
    override val titleHighs = "Sare u kacyada"
    override val titleDays = "Maalinba maalin"
    override val titleBefore = "Marka loo eego maalmihii ka horreeyey"
    override val titleGoals = "Hadafyada"
    override val titleDoctor = "Waxa aad dhakhtarka u sheegto"
    override val titleQuestions = "Su'aalaha aad qaadato"
    override val titleCare = "Erayada taxaddarka"

    override val noValues = "Muddadan weli qiimayaal ma jiraan. Dareemaha (sensor) sawir, ama daar LibreLinkUp, abkana wuxuu sheegi doonaa waxa uu arko."
    override val period = "Laga bilaabo %1\$s ilaa %2\$s. Waa %3\$s. Qiimayaal ayaa ku jira %4\$d %% waqtigaas."
    override val quarters = "Abku wuxuu tiriyey %1\$d rubac saacadood oo qiimayaal leh; mid walba waa 15 daqiiqo."

    override val steady = "Deggan, inta badanna xadka ku jirta. Celcelis %1\$s, xadka ku jirta %2\$d %% waqtiga."
    override val inRange = "Inta badan xadka ku jirta. Celcelis %1\$s, xadka ku jirta %2\$d %% waqtiga."
    override val oftenHigh = "Badanaa waa sare. Celcelis %1\$s, waxay ka sarraysay %2\$s muddo %3\$d %% waqtiga."
    override val lows = "Hoos u dhac ayaa jiray. Celcelis %1\$s, way ka hoosaysay %2\$s muddo %3\$d %% waqtiga."
    override val veryLow = "Waxaa jiray qiime ka hooseeya %1\$s. Taasi waa qiime aad u hooseeya: dhakhtarka ama kalkaalisada sonkorta u sheeg dhaqso."

    override val mean = "Celceliska gulukoosta: %1\$s."
    override val extremes = "Ugu hooseeya %1\$s, ugu sarreeya %2\$s. Faraqa ugu weyn wuxuu ahaa %3\$s."
    override val gmi = "Celcelis sidan ah wuxuu u dhigmaa HbA1c qiyaas ah %1\$s (waa qiyaas, qiimayaasha oo keliya). Baaritaan dhiig oo keliya ayaa si hubaal ah u sheegi kara."
    override val gmiTooShort = "Muddadu aad bay u gaaban tahay in HbA1c la qiyaaso: waxaa loo baahan yahay 14 maalmood oo qiimayaal leh."

    override val rangeLine = "Xadka %1\$s ilaa %2\$s ku jirta: %3\$d %% waqtiga."
    override val sidesLine = "Ka hooseeya %1\$s: %2\$d %% waqtiga. Ka sarreeya %3\$s: %4\$d %% waqtiga."
    override val tightLine = "Xadka dhow %1\$s ilaa %2\$s: %3\$d %% waqtiga."

    override val cvLine = "Qiimayaashu waxay ku wareegaan celceliska %1\$d %% (tan waxaa la yiraahdaa CV)."
    override val cvSteady = "Taasi waa %1\$d %% ama ka yar, waxaana la yiraahdaa deggan: qiimayaashu waxay u dhow yihiin celceliska."
    override val cvUnsteady = "Taasi way ka badan tahay %1\$d %%, waxaana la yiraahdaa aan degganayn: sare iyo hoos ayaa is daba socda."

    override val calmestHour = "Saacadda ugu deggan maalinta: %1\$02d:00, celcelis %2\$s."
    override val highestHour = "Saacadda ugu sarreysa maalinta: %1\$02d:00, celcelis %2\$s."

    override val partLine = "%1\$s: xadka ku jirta %2\$d %%, ka hooseeya %3\$d %%, ka sarreeya %4\$d %%."
    override val partNight = "Habeen (00:00 ilaa 06:00)"
    override val partMorning = "Subax (06:00 ilaa 12:00)"
    override val partAfternoon = "Gelinka dambe (12:00 ilaa 18:00)"
    override val partEvening = "Fiid (18:00 ilaa 24:00)"

    override val morningRose = "Muddo %1\$d habeen, gulukoostu waxay korodhay %2\$s inta u dhaxaysa 2:30 iyo 5:30 aroornimo."
    override val morningFell = "Muddo %1\$d habeen, gulukoostu waxay hoos u dhacday %2\$s inta u dhaxaysa 2:30 iyo 5:30 aroornimo."
    override val morningDawn = "Korodhka noocan ah ee aroortii hore waa wax caadi ah, wuxuuna leeyahay magac: dhacdada waaberiga (dawn phenomenon). Dhakhtarka u sheeg."
    override val morningNone = "Habeeno aad u yar ayaa qiimayaal u leh labada waqti (waxaa loo baahan yahay 3 habeen), sidaa darteed waxba lagama oran karo aroortii hore."

    override val lowCount = "%1\$d hoos u dhac oo socday 15 daqiiqo ama ka badan (ka hooseeya %3\$s)."
    override val lowLongest = "Kii ugu dheeraa wuxuu socday %1\$s, wuxuuna bilaabmay %2\$s."
    override val lowNight = "Habeenkii (saqda dhexe ilaa 06:00): %1\$d hoos u dhac."
    override val lowNightCare = "Hoos u dhaca habeenkii waa waxa ugu muhiimsan ee aad dhakhtarka u sheegto."
    override val lowNone = "Ma jiro hoos u dhac socday 15 daqiiqo ama ka badan. Taasi waa fiican tahay."

    override val highCount = "%1\$d sare u kac oo socday 15 daqiiqo ama ka badan (ka sarreeya %3\$s)."
    override val highLongest = "Kii ugu dheeraa wuxuu socday %1\$s, wuxuuna bilaabmay %2\$s."
    override val highSpikes = "%1\$d kor u kac degdeg ah: korodh %3\$s ama ka badan muddo saacad ah. Kan ugu weyn wuxuu ahaa abbaare %4\$02d:00."
    override val highNone = "Ma jiro sare u kac socday 15 daqiiqo ama ka badan."

    override val dayLine = "%1\$s: xadka ku jirta %2\$d %%, celcelis %3\$s, %4\$d hoos u dhac, %5\$d sare u kac."

    override val beforeLine = "Maalmihii ka horreeyey, celcelisku wuxuu ahaa %1\$s, xadkana wuxuu ku jiray %2\$d %% waqtiga. Hadda waa %3\$s iyo %4\$d %%."
    override val beforeDown = "Marka loo eego markaas, qiimayaashii hoos ayey u dhaceen."
    override val beforeUp = "Marka loo eego markaas, qiimayaashii kor ayey u kaceen."
    override val beforeSame = "Marka loo eego markaas, wax badan isma beddelin."

    override val goalLine = "%1\$s: %2\$s. Hadafka: %3\$s. %4\$s"
    override val goalInRange = "Waqtiga xadka ku jira"
    override val goalLow = "Waqtiga ka hooseeya %1\$s"
    override val goalVeryLow = "Waqtiga ka hooseeya %1\$s"
    override val goalHigh = "Waqtiga ka sarreeya %1\$s"
    override val goalVeryHigh = "Waqtiga ka sarreeya %1\$s"
    override val goalVariation = "Kala duwanaanshaha (CV)"
    override val goalMet = "Waa la gaaray."
    override val goalMissed = "Lama gaarin."

    override val doctorNight = "%1\$d hoos u dhac oo habeenkii ah. Weydii waxa la beddeli karo si ay u joogsadaan."
    override val doctorLows = "%1\$d hoos u dhac muddo %2\$s. Sheeg goorta ay yimaadaan iyo waxa aad samaynaysay."
    override val doctorHigh = "Waxay ka sarraysay %1\$s muddo %2\$d %% waqtiga, way ka badan tahay hadafka 25 %%."
    override val doctorCv = "Qiimayaashu ma deggana (CV %1\$d %%), way ka badan tahay hadafka 36 %%."
    override val doctorDawn = "Gulukoostu waxay korodhaa aroortii hore (dhacdada waaberiga)."
    override val doctorGaps = "Waqtiga %1\$d %% oo keliya ayaa qiimayaal leh, sidaa darteed sawirku waa daloolsan yahay."
    override val doctorNothing = "Wax muuqda kuma jiro tirooyinka. Warbixintan ha u qaado booqashadaada xigta si kastaba."

    override val question1 = "Hadafyadaydu ma igu habboon yihiin? Kaygu waa %1\$s ilaa %2\$s."
    override val question2 = "Maxaan sameeyaa marka qiimuhu ka hooseeyo %1\$s?"
    override val question3 = "Maxaan sameeyaa marka uu ka sarreeyo %1\$s?"
    override val question4 = "Daawooyinkaygu ma u baahan yihiin beddel? Adiga oo keliya ayaa go'aamin kara, abkani ma go'aamin karo."
    override val question5 = "Imisa jeer ayaan is eegaa, goormaadse kuu soo wacaa?"

    override val care = "Qoraalkani wuxuu ka yimid xeerar go'an oo abka ku jira, ee kama iman dhakhtar. Wuxuu qeexayaa waxa dareemuhu cabbiray. Ma bixiyo qiyaas daawo, daawo iyo daaweyn toona. Weligaa ha beddelin insulin ama daawo sababtiisa darted. Abkani ma aha qalab caafimaad."

    override fun episodes(count: Int): String = ""
    override fun length(days: Int): String = if (days <= 1) "24 saacadood" else "$days maalmood"
}

internal object Arabic : Words() {
    override val titlePeriod = "الفترة"
    override val titleLine = "في سطر واحد"
    override val titleMean = "المعدّل"
    override val titleRange = "الوقت داخل المدى"
    override val titleSteady = "مدى ثبات القيم"
    override val titleHours = "ساعة بساعة"
    override val titleParts = "أجزاء اليوم"
    override val titleMorning = "الصباح الباكر"
    override val titleLows = "الانخفاضات"
    override val titleHighs = "الارتفاعات"
    override val titleDays = "يومًا بيوم"
    override val titleBefore = "مقارنة بالأيام التي قبلها"
    override val titleGoals = "الأهداف"
    override val titleDoctor = "ما تخبر به طبيبك"
    override val titleQuestions = "أسئلة تأخذها معك"
    override val titleCare = "كلمات الحذر"

    override val noValues = "لا توجد قيم في هذه الفترة بعد. امسح الحسّاس، أو شغّل LibreLinkUp، وسيقول التطبيق ما يراه."
    override val period = "من %1\$s إلى %2\$s. أي %3\$s. توجد قيم في %4\$d %% من هذا الوقت."
    override val quarters = "عدّ التطبيق %1\$d ربع ساعة فيها قيم؛ كل ربع ساعة يعني 15 دقيقة."

    override val steady = "ثابتة، وداخل المدى الهدف معظم الوقت. المعدّل %1\$s، وداخل المدى %2\$d %% من الوقت."
    override val inRange = "داخل المدى الهدف معظم الوقت. المعدّل %1\$s، وداخل المدى %2\$d %% من الوقت."
    override val oftenHigh = "مرتفعة كثيرًا. المعدّل %1\$s، وفوق %2\$s لمدة %3\$d %% من الوقت."
    override val lows = "كانت هناك انخفاضات. المعدّل %1\$s، وأقل من %2\$s لمدة %3\$d %% من الوقت."
    override val veryLow = "كانت هناك قيمة أقل من %1\$s. هذه قيمة منخفضة جدًا: أخبر طبيبك أو ممرضة السكري قريبًا."

    override val mean = "معدّل الجلوكوز: %1\$s."
    override val extremes = "الأدنى %1\$s، والأعلى %2\$s. وأوسع فرق بينهما %3\$s."
    override val gmi = "هذا المعدّل يقابل HbA1c بنحو %1\$s (تقدير من القيم وحدها). تحليل الدم وحده يؤكد ذلك."
    override val gmiTooShort = "الفترة أقصر من أن تُقدَّر منها HbA1c: يلزم 14 يومًا فيها قيم."

    override val rangeLine = "داخل المدى %1\$s إلى %2\$s: %3\$d %% من الوقت."
    override val sidesLine = "أقل من %1\$s: %2\$d %% من الوقت. أعلى من %3\$s: %4\$d %% من الوقت."
    override val tightLine = "داخل المدى الضيق %1\$s إلى %2\$s: %3\$d %% من الوقت."

    override val cvLine = "القيم تتحرك حول المعدّل بنسبة %1\$d %% (وهذا ما يسمى CV)."
    override val cvSteady = "هذا %1\$d %% أو أقل، ويسمى ثباتًا: القيم تبقى قريبة من المعدّل."
    override val cvUnsteady = "هذا أكثر من %1\$d %%， ويسمى عدم ثبات: الارتفاعات والانخفاضات تتتابع بسرعة."

    override val calmestHour = "أهدأ ساعة في اليوم: %1\$02d:00، بمعدّل %2\$s."
    override val highestHour = "أعلى ساعة في اليوم: %1\$02d:00، بمعدّل %2\$s."

    override val partLine = "%1\$s: داخل المدى %2\$d %%， وأقل %3\$d %%， وأعلى %4\$d %%."
    override val partNight = "الليل (00:00 إلى 06:00)"
    override val partMorning = "الصباح (06:00 إلى 12:00)"
    override val partAfternoon = "بعد الظهر (12:00 إلى 18:00)"
    override val partEvening = "المساء (18:00 إلى 24:00)"

    override val morningRose = "خلال %1\$d ليلة، ارتفع الجلوكوز %2\$s بين 2:30 و5:30 صباحًا."
    override val morningFell = "خلال %1\$d ليلة، انخفض الجلوكوز %2\$s بين 2:30 و5:30 صباحًا."
    override val morningDawn = "لهذا الارتفاع في الصباح الباكر اسم، وهو أمر شائع: ظاهرة الفجر. اذكره لطبيبك."
    override val morningNone = "ليالٍ قليلة فيها قيم في الوقتين (يلزم 3 ليالٍ)، فلا يقال شيء عن الصباح الباكر."

    override val lowCount = "%1\$d انخفاضًا استمر 15 دقيقة أو أكثر (أقل من %3\$s)."
    override val lowLongest = "أطولها استمر %1\$s وبدأ في %2\$s."
    override val lowNight = "في الليل (من منتصف الليل إلى 06:00): %1\$d انخفاضًا."
    override val lowNightCare = "انخفاضات الليل هي أهم ما تخبر به طبيبك."
    override val lowNone = "لا يوجد انخفاض استمر 15 دقيقة أو أكثر. هذا جيد."

    override val highCount = "%1\$d ارتفاعًا استمر 15 دقيقة أو أكثر (أعلى من %3\$s)."
    override val highLongest = "أطولها استمر %1\$s وبدأ في %2\$s."
    override val highSpikes = "%1\$d ارتفاعًا سريعًا: زيادة %3\$s أو أكثر في ساعة واحدة. وأكبرها كان نحو الساعة %4\$02d:00."
    override val highNone = "لا يوجد ارتفاع استمر 15 دقيقة أو أكثر."

    override val dayLine = "%1\$s: داخل المدى %2\$d %%， المعدّل %3\$s، %4\$d انخفاضًا، %5\$d ارتفاعًا."

    override val beforeLine = "في الأيام التي قبلها كان المعدّل %1\$s وداخل المدى %2\$d %% من الوقت. والآن %3\$s و%4\$d %%."
    override val beforeDown = "مقارنة بذلك الوقت، القيم نزلت."
    override val beforeUp = "مقارنة بذلك الوقت، القيم ارتفعت."
    override val beforeSame = "مقارنة بذلك الوقت، بقيت كما هي تقريبًا."

    override val goalLine = "%1\$s: %2\$s. الهدف: %3\$s. %4\$s"
    override val goalInRange = "الوقت داخل المدى"
    override val goalLow = "الوقت أقل من %1\$s"
    override val goalVeryLow = "الوقت أقل من %1\$s"
    override val goalHigh = "الوقت أعلى من %1\$s"
    override val goalVeryHigh = "الوقت أعلى من %1\$s"
    override val goalVariation = "التقلّب (CV)"
    override val goalMet = "تحقق."
    override val goalMissed = "لم يتحقق."

    override val doctorNight = "%1\$d انخفاضًا في الليل. اسأل ما الذي يمكن تغييره لتتوقف."
    override val doctorLows = "%1\$d انخفاضًا في %2\$s. قل متى تأتي وماذا كنت تفعل."
    override val doctorHigh = "أعلى من %1\$s لمدة %2\$d %% من الوقت، أكثر من الهدف 25 %%."
    override val doctorCv = "القيم غير ثابتة (CV %1\$d %%), أكثر من الهدف 36 %%."
    override val doctorDawn = "الجلوكوز يرتفع في الصباح الباكر (ظاهرة الفجر)."
    override val doctorGaps = "القيم تغطي %1\$d %% فقط من الوقت، فالصورة فيها فجوات."
    override val doctorNothing = "لا شيء يلفت النظر في الأرقام. ومع ذلك خذ هذا التقرير إلى زيارتك القادمة."

    override val question1 = "هل أهدافي مناسبة لي؟ أهدافي من %1\$s إلى %2\$s."
    override val question2 = "ماذا أفعل عندما تكون القيمة أقل من %1\$s؟"
    override val question3 = "ماذا أفعل عندما تبقى أعلى من %1\$s؟"
    override val question4 = "هل تحتاج أدويتي إلى تغيير؟ أنت وحدك تقرر ذلك، لا هذا التطبيق."
    override val question5 = "كم مرة أفحص، ومتى أتصل بك؟"

    override val care = "هذا النص كتبته قواعد ثابتة داخل التطبيق، لا طبيب. إنه يصف ما قاسه الحسّاس. لا يعطي جرعة ولا دواء ولا علاجًا. لا تغيّر الأنسولين أو أي دواء أبدًا بسببه. هذا التطبيق ليس جهازًا طبيًا."

    override fun episodes(count: Int): String = ""
    override fun length(days: Int): String = if (days <= 1) "24 ساعة" else "$days أيام"
}
