/*
 * Copyright (C) 2021-2022 Federico Dossena
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.dosse.circolaribot;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendMessage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import org.ini4j.Wini;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 *
 * @author dosse
 */
public class Main {

    private static final String URL = "https://www.galileicrema.edu.it/arc_circolari?items_per_page=All&field_anno_scolastico_value=$$$ANNO$$$"; //URL da tenere controllato ($$$ANNO$$$ viene sostituito dall'AS corrente, ad esempio 2022-2023)
    private static final String CONFIG_FILENAME = "config.ini"; //file da cui caricare la configurazione
    private static final String STATE_FILENAME = "state-v1.dat"; //file su cui salvare/caricare lo stato (per evitare di ripetere i post in caso di riavvio del bot)

    private static final long CHECK_NEW_INTERVAL = 600000; //controlla nuove circolari ogni 10 minuti
    private static final long CHECK_PDFUPDATE_EVERY_INTERVALS = 36; //controlla aggiornamenti delle circolari vecchie ogni 36 intervalli (6 ore)
    private static final int CHECK_PDFUPDATE_LAST_N = 50; //controlla solo le ultime 50 circolari per cambiamenti
    private static final long DELAY_BETWEEN_POSTS = 5000; //sleep di 5 secondi tra i post per evitare il rate limiting di telegram
    private static final long DELAY_BETWEEN_RECONNECTS = 60000; //se la connessione a telegram va giù, ritenta ogni 1 minuto
    private static final long DELAY_BETWEEN_PDFHASHES = 3000; //aspetta 3 secondi tra un download di un PDF e un altro
    
    private static final String APP_NAME="CircolariBot", APP_VERSION="1.2.1";
    private static final String USER_AGENT=APP_NAME+"/"+APP_VERSION;

    private static String token = null; //ID del bot fornito da botfather
    private static String channel = null; //ID del canale su cui postare le notifiche (il bot deve essere admin)
    private static TelegramBot bot = null; //collegamento a telegram

    private static boolean testMode = false;

    public static void main(String[] args) {
        System.out.println("--- "+APP_NAME+" v"+APP_VERSION+" ---");
        if (args.length != 0 && args[0].equalsIgnoreCase("--test")) {
            testMode = true;
            System.out.println("Modalità test attiva, i messaggi verranno scritti sul terminale anzichè su telegram");
        }
        loadConfig();
        loadState();
        for (;;) {
            long ts = System.currentTimeMillis();
            botLoop();
            sleep(CHECK_NEW_INTERVAL - (System.currentTimeMillis() - ts));
        }
    }

    private static void loadConfig() {
        try {
            Wini ini = new Wini(new File(CONFIG_FILENAME));
            token = ini.get("CircolariBot", "TOKEN");
            channel = ini.get("CircolariBot", "CHANNEL_ID");
        } catch (Throwable t) {
            System.err.println("Errore: impossibile caricare la configurazione\n\n"
                    + "Il file " + CONFIG_FILENAME + " deve avere la seguente sintassi:\n"
                    + "[CircolariBot]\n"
                    + "TOKEN=id fornito da botfather\n"
                    + "CHANNEL_ID=id del canale di cui il bot è admin\n\n");
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static HashMap<String, Long> alreadyPosted; //map url->timestamp dei post (per distinguere elementi nuovi da quelli vecchi)
    private static HashMap<String, byte[]> pdfHashes;  //map url->hash dei PDF delle circolari (per rilevare aggiornamenti di circolari già viste)

    private static int loopCounter = 0;

    private static void botLoop() {
        ArrayList<SendMessage> postsToSend = new ArrayList<>();
        try {
            String annoScolasticoCorrente;
            Calendar cal = Calendar.getInstance();
            if (cal.get(Calendar.MONTH) >= Calendar.SEPTEMBER) {
                annoScolasticoCorrente = cal.get(Calendar.YEAR) + "-" + (cal.get(Calendar.YEAR) + 1);
            } else {
                annoScolasticoCorrente = (cal.get(Calendar.YEAR) - 1) + "-" + cal.get(Calendar.YEAR);
            }
            Document doc = Jsoup.connect(URL.replace("$$$ANNO$$$", annoScolasticoCorrente)).userAgent(USER_AGENT).get();
            Elements circolari = doc.select("div.view-circolari-archivio-new div.view-content tr");
            if (circolari.isEmpty()) {
                System.err.println("Nessuna circolare estratta, forse è cambiato qualcosa nel sito?");
            }
            boolean checkForPdfUpdates = ++loopCounter % CHECK_PDFUPDATE_EVERY_INTERVALS == 0; //quando è true controlla gli aggiornamenti delle circolari vecchie anzichè cercare circolari nuove
            int n = 0;
            for (Element e : circolari) {
                try {
                    String numero = e.select("td.views-field-field-circolare-protocollo").first().text();
                    String titolo = e.select("td.views-field-title > a").first().text();
                    String descrizione = e.select("td.views-field-title > p").first().text();
                    String link = e.select("td.views-field-title > a").first().attributes().get("href");
                    String data = e.select("span.date-display-single").first().text();
                    if (checkForPdfUpdates) {
                        if (n < CHECK_PDFUPDATE_LAST_N) {
                            byte[] oldHash = pdfHashes.get(link);
                            if (oldHash != null) {
                                byte[] newHash = getPdfHash(link);
                                if (newHash != null && newHash.length == oldHash.length) {
                                    boolean updated = false;
                                    for (int i = 0; i < newHash.length; i++) {
                                        if (newHash[i] != oldHash[i]) {
                                            updated = true;
                                            break;
                                        }
                                    }
                                    if (updated) {
                                        postsToSend.add(new SendMessage(channel, "Aggiornamento circolare " + numero + " del " + data + "\n" + titolo + "\n" + descrizione + "\n" + (link + "?ts=" + System.currentTimeMillis())));
                                        pdfHashes.put(link, newHash);
                                    }
                                }
                            }
                            sleep(DELAY_BETWEEN_PDFHASHES);
                        }
                    } else {
                        if (alreadyPosted.get(link) == null) {
                            byte[] hash = getPdfHash(link);
                            if (hash == null) {
                                System.err.println("Impossibile ottenere il file della circolare");
                            } else {
                                postsToSend.add(new SendMessage(channel, "Circolare " + numero + " del " + data + "\n" + titolo + "\n" + descrizione + "\n" + link));
                                alreadyPosted.put(link, System.nanoTime());
                                pdfHashes.put(link, hash);
                            }
                        }
                    }
                    n++;
                } catch (Throwable t) {
                    System.err.println("Fallita l'estrazione delle informazioni dalla circolare. Dati grezzi: " + e);
                    t.printStackTrace(System.err);
                }
            }
        } catch (Throwable t) {
            System.out.println("Errore durante il recupero delle circolari");
            t.printStackTrace(System.err);
        }
        if (!postsToSend.isEmpty()) {
            if (bot == null) {
                connectToTelegram();
            }
            Collections.reverse(postsToSend);
            for (SendMessage m : postsToSend) {
                for (;;) {
                    try {
                        if (testMode) {
                            System.out.println("Post:\n" + m.getParameters().get("text") + "\n\n");
                        } else {
                            bot.execute(m);
                        }
                        break;
                    } catch (Throwable t) {
                        System.err.println("Errore durante l'invio di un post, tentativo di riconnessione");
                        connectToTelegram();
                    }
                }
                sleep(DELAY_BETWEEN_POSTS);
            }
            saveState();
        }
    }

    private static void connectToTelegram() {
        for (;;) {
            try {
                bot = new TelegramBot(token);
                break;
            } catch (Throwable t) {
                System.err.println("Errore durante la connessione a telegram, riprovo tra poco");
                t.printStackTrace(System.err);
                sleep(DELAY_BETWEEN_RECONNECTS);
            }
        }
    }

    private static void loadState() {
        ObjectInputStream ois = null;
        HashMap<String, Long> map;
        HashMap<String, byte[]> hashes;
        try {
            ois = new ObjectInputStream(new FileInputStream(STATE_FILENAME));
            map = (HashMap<String, Long>) ois.readObject();
            try {
                hashes = (HashMap<String, byte[]>) ois.readObject();
            } catch (Throwable t) {
                hashes = new HashMap<String, byte[]>();
                System.err.println("Caricato stato salvato da una versione precedente del bot");
            }
        } catch (Throwable t) {
            System.err.println("Stato salvato mancante o corrotto, il bot riparte da zero");
            t.printStackTrace(System.err);
            map = new HashMap<String, Long>();
            hashes = new HashMap<String, byte[]>();
        }
        alreadyPosted = map;
        pdfHashes = hashes;
        System.out.println("Caricati "+alreadyPosted.size()+" link e "+pdfHashes.size()+" hash");
        try {
            ois.close();
        } catch (Throwable ex) {
        }
    }

    private static void saveState() {
        ObjectOutputStream oos = null;
        try {
            oos = new ObjectOutputStream(new FileOutputStream(STATE_FILENAME));
            oos.writeObject(alreadyPosted);
            oos.writeObject(pdfHashes);
            oos.flush();
        } catch (Throwable t) {
            System.err.println("Impossibile salvare lo stato");
        }
        try {
            oos.close();
        } catch (Throwable ex) {
        }
    }

    private static final void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
        }
    }

    private static final byte[] getPdfHash(String url) {
        byte[] data = null;
        InputStream in = null;
        try {
            URLConnection c=new URL(url).openConnection();
            c.setDefaultUseCaches(false);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.connect();
            in = c.getInputStream();
        } catch (Throwable ex) {
            try {
                in.close();
            } catch (Throwable t) {
            }
            return null;
        }
        try {
            data = in.readAllBytes();
        } catch (Throwable ex) {
            try {
                in.close();
            } catch (Throwable t) {
            }
            return null;
        }
        try {
            in.close();
        } catch (Throwable t) {
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return md.digest(data);
        } catch (Throwable t) {
            return null;
        }
    }

}
