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
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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

    private static final long CHECK_INTERVAL = 600000; //controlla ogni 10 minuti
    private static final long DELAY_BETWEEN_POSTS = 5000; //sleep di 5 secondi tra i post per evitare il rate limiting di telegram
    private static final long DELAY_BETWEEN_RECONNECTS = 60000; //se la connessione a telegram va giù, ritenta ogni 1 minuto

    private static String token = null; //ID del bot fornito da botfather
    private static String channel = null; //ID del canale su cui postare le notifiche (il bot deve essere admin)
    private static TelegramBot bot = null; //collegamento a telegram

    public static void main(String[] args) {
        System.out.println("--- CircolariBot v1.1 ---");
        loadConfig();
        loadState();
        for (;;) {
            long ts = System.currentTimeMillis();
            botLoop();
            sleep(CHECK_INTERVAL - (System.currentTimeMillis() - ts));
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

    private static void botLoop() {
        ArrayList<SendMessage> postsToSend = new ArrayList<>();
        try {
            String annoScolasticoCorrente;
            Calendar cal=Calendar.getInstance();
            if(cal.get(Calendar.MONTH)>=Calendar.SEPTEMBER){
                annoScolasticoCorrente=cal.get(Calendar.YEAR)+"-"+(cal.get(Calendar.YEAR)+1);
            }else{
                annoScolasticoCorrente=(cal.get(Calendar.YEAR)-1)+"-"+cal.get(Calendar.YEAR);
            }
            Document doc = Jsoup.connect(URL.replace("$$$ANNO$$$", annoScolasticoCorrente)).get();
            Elements circolari = doc.select("div.view-circolari-archivio-new div.view-content tr");
            if (circolari.isEmpty()) {
                System.err.println("Nessuna circolare estratta, forse è cambiato qualcosa nel sito?");
            }
            for (Element e : circolari) {
                try {
                    String numero = e.select("td.views-field-field-circolare-protocollo").first().text();
                    String titolo = e.select("td.views-field-title > a").first().text();
                    String descrizione = e.select("td.views-field-title > p").first().text();
                    String link = e.select("td.views-field-title > a").first().attributes().get("href");
                    String data = e.select("span.date-display-single").first().text();
                    if (alreadyPosted.get(link) == null) {
                        postsToSend.add(new SendMessage(channel, "Circolare " + numero + " del " + data + "\n" + titolo + "\n" + descrizione + "\n" + link));
                        alreadyPosted.put(link, System.nanoTime());
                    }
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
                        bot.execute(m);
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
        try {
            ois = new ObjectInputStream(new FileInputStream(STATE_FILENAME));
            map = (HashMap<String, Long>) ois.readObject();
        } catch (Throwable t) {
            System.err.println("Stato salvato mancante o corrotto, il bot riparte da zero");
            t.printStackTrace(System.err);
            map = new HashMap<String, Long>();
        }
        alreadyPosted = map;
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
}
