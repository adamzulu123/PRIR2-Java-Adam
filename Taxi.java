/**
 * Interfejs pojedynczej taksówki
 */
public interface Taxi {
    /**
     * Unikalny numer identyfikujący taksówkę. Numery taksówek mogą być dowolne.
     *
     * @return numer identyfikujący taksówkę
     */
    int numer();

    /**
     * Realizacja zlecenia o podanym numerze. Wątek, który wywołał metodę, zostaje
     * użyty do realizacji zlecenia. Czas realizacji zlecenia nie jest z góry znany.
     * O ile nie doszło do awarii taksówki, realizacja zlecenia kończy się wraz z
     * zakończeniem metody. Wątek wywołujący metodę jest przez okres potrzebny do
     * realizacji zlecenia **zablokowany**.
     *
     * @param numerZlecenia numer zlecenia do realizacji
     */
    void wykonajZlecenie(int numerZlecenia);
}