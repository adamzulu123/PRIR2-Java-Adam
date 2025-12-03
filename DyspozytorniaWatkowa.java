import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class DyspozytorniaWatkowa implements Dyspozytornia {
    private final AtomicInteger zlecenieId = new AtomicInteger(1);

    //zlecenia
    Comparator<Zlecenie> zlecenieComparator = Comparator
            .comparing(Zlecenie::priority).reversed()
            .thenComparing(Zlecenie::createdAt);
    private final PriorityBlockingQueue<Zlecenie> zlecenieQueue = new PriorityBlockingQueue<>(100, zlecenieComparator);

    //TAXI
    private final ConcurrentHashMap<Integer, TaxiThread> taxiMap = new ConcurrentHashMap<>();

    @Override
    public void flota(Set<Taxi> flota) {
        for (Taxi taxi : flota) {
            TaxiThread taxiThread = new TaxiThread(taxi, zlecenieQueue, this);
            taxiMap.put(taxi.numer(), taxiThread);
            Thread thread = new Thread(taxiThread);
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    public int zlecenie() {
        return 0;
    }

    @Override
    public void awaria(int numer, int numerZlecenia) {

    }

    @Override
    public void naprawiono(int numer) {

    }

    @Override
    public Set<Integer> koniecPracy() {
        return Set.of();
    }
}


class TaxiThread implements Runnable {
    private final Taxi taxi;
    private final PriorityBlockingQueue<Zlecenie> zlecenieQueue;
    private final Dyspozytornia dyspozytornia;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition awariaQueue = lock.newCondition();
    // private final Condition noweZlecenieQueue = lock.newCondition(); // -> możliwe że useless

    private volatile TaxiState state = TaxiState.WAITING;

    TaxiThread(Taxi taxi, PriorityBlockingQueue<Zlecenie> kolejka,
              DyspozytorniaWatkowa dyspozytornia) {
        this.taxi = taxi;
        this.zlecenieQueue = kolejka;
        this.dyspozytornia = dyspozytornia;
    }

    @Override
    public void run() {

    }



    public TaxiState getState() { return state; }
    public void setState(TaxiState state) { this.state = state; }
}

record Zlecenie(int id, int priority, long createdAt) {}
enum TaxiState { WAITING, RUNNING, BROKEN }







