import java.util.Set;

/**
 * Interfejs systemu obsługującego taksówki.
 */
public interface Dyspozytornia {
    /**
     * Metoda przekazuje Dyspozytorni flotę taksówek.
     * * @param flota zbiór taksówek
     */
    void flota(Set<Taxi> flota);

    /**
     * Klient zamawia taksówkę, tworząc nowe zlecenie. Metoda zwraca unikatowy numer
     * zlecenia.
     * * @return numer zlecenia
     */
    int zlecenie();

    /**
     * Kierowca zgłasza awarię taksówki.
     * * @param numer         numer taksówki, która uległa awarii
     * @param numerZlecenia numer niewykonanego zlecenia
     */
    void awaria(int numer, int numerZlecenia);

    /**
     * Kierowca zgłasza naprawę taksówki.
     * * @param numer numer taksówki, która została naprawiona
     */
    void naprawiono(int numer);

    /**
     * Koniec pracy Dyspozytorni. Metoda zwraca zbiór wszystkich numerów zleceń,
     * które nie zostały przydzielone taksówkom. Przydzielone zlecenia nie są
     * umieszczane w zbiorze. Jeśli doszło do awarii taksówki i zlecenie nie zostało
     * jeszcze przydzielone, także jego numer ma być umieszczony w wynikowym zbiorze.
     * Jeśli rozwiązanie używa egzekutorów, metoda koniecPracy powinna zakończyć ich pracę.
     * * @return zbiór numerów zleceń, które nie zostały przydzielone.
     */
    Set<Integer> koniecPracy();
}