import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class DyspozytorniaWatkowa implements Dyspozytornia {
    private final AtomicInteger zlecenieId = new AtomicInteger(0);

    //zlecenia -> ta kolejka ma wewnętrzny RenstrantLock
    Comparator<Zlecenie> zlecenieComparator = Comparator
            .comparing(Zlecenie::priority).reversed()
            .thenComparing(Zlecenie::createdAt);
    private final PriorityBlockingQueue<Zlecenie> zlecenieQueue = new PriorityBlockingQueue<>(100, zlecenieComparator);

    // śledzenie przydzielonych zleceń: Zlecenie ID -> Taxi numer
    private final ConcurrentHashMap<Integer, Integer> assignedOrders = new ConcurrentHashMap<>();

    // Taxi
    private final ConcurrentHashMap<Integer, TaxiThread> allTaxis = new ConcurrentHashMap<>();
    private final Set<Integer> availableTaxis = ConcurrentHashMap.newKeySet();
    private final ReentrantLock taxiLock = new ReentrantLock();
    private volatile Integer brokenTaxiId = null; // warunki -> jeden broken na raz

    private volatile boolean shuttingDownDyspozytornia = false;

    @Override
    public void flota(Set<Taxi> flota) {
        for (Taxi taxi : flota) {
            TaxiThread taxiThread = new TaxiThread(taxi, this);
            allTaxis.put(taxi.numer(), taxiThread);
            availableTaxis.add(taxi.numer());
            Thread thread = new Thread(taxiThread);
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    public int zlecenie() {
        if (shuttingDownDyspozytornia) {
            throw new IllegalStateException("Dyspozytornia is shutting down");
        }
        int id = zlecenieId.incrementAndGet();
        Zlecenie zlecenie = new Zlecenie(id, 0, Instant.now());
        zlecenieQueue.offer(zlecenie);

        tryAssignZlecenie();
        return id;
    }

    @Override
    public void awaria(int numer, int numerZlecenia) {
        TaxiThread taxiThread = allTaxis.get(numer);
        if (taxiThread == null) throw new RuntimeException("Taxi thread not found");
        if (taxiThread.getState() == TaxiState.BROKEN) throw new IllegalStateException("Broken Taxi cannot be broken again");
        if (brokenTaxiId != null) throw new IllegalStateException("Two taxi cannot be broken at the same time");

        taxiThread.markTaxiAsBroken();
        brokenTaxiId = numer;

        //dla bezpieństwa - gdy awaria taxi bez zadania
        taxiLock.lock();
        try {
            availableTaxis.remove(numer);
        } finally {
            taxiLock.unlock();
        }

        Integer wasAssigned = assignedOrders.remove(numerZlecenia);
        if (wasAssigned == null) {
            // edge case który nie jestem pewny czy powinien zostać obsłużony
            System.out.println("Zlecenie number: " + numerZlecenia + " wasn't assigned, only taxi will be marked as broken");
        } else {
            Zlecenie zlecenie = new Zlecenie(numerZlecenia, 1, Instant.now());
            zlecenieQueue.offer(zlecenie);
        }

        tryAssignZlecenie();
    }

    @Override
    public void naprawiono(int numer) {
        TaxiThread taxiThread = allTaxis.get(numer);
        if (taxiThread == null) throw new IllegalStateException("Taxi thread not found");
        if (taxiThread.getState() != TaxiState.BROKEN) throw new IllegalStateException("Taxi not broken");

        taxiThread.markTaxiAsRepaired();
        brokenTaxiId = null;

        taxiLock.lock();
        try {
            availableTaxis.add(numer);
        } finally {
            taxiLock.unlock();
        }

        tryAssignZlecenie();
    }

    @Override
    public Set<Integer> koniecPracy() {
        shuttingDownDyspozytornia = true;

        for (TaxiThread taxiThread : allTaxis.values()) {
            taxiThread.stop();
        }

        Set<Integer> resztaPracy = new HashSet<>();
        while (!zlecenieQueue.isEmpty()) {
            resztaPracy.add(zlecenieQueue.poll().id());
        }

        return resztaPracy;
    }

    public boolean isShuttingDownDyspozytornia() {
        return shuttingDownDyspozytornia;
    }

    private void tryAssignZlecenie() {
        if (shuttingDownDyspozytornia) return;

        taxiLock.lock();
        try {
            while (!zlecenieQueue.isEmpty() && !availableTaxis.isEmpty()) {
                Zlecenie zlecenie = zlecenieQueue.poll();
                if (zlecenie == null) break;

                Integer taxiNumer = availableTaxis.iterator().next();
                availableTaxis.remove(taxiNumer);

                TaxiThread taxiThread = allTaxis.get(taxiNumer);
                if (taxiThread != null) {
                    assignedOrders.put(zlecenie.id(), taxiNumer);
                    taxiThread.assignZlecenie(zlecenie);
                }
            }
        } finally {
            taxiLock.unlock();
        }
    }

    void taxiAfterZlecenie(int taxiNumer, int zlecenieId) {
        if (shuttingDownDyspozytornia) return;

        assignedOrders.remove(zlecenieId);

        taxiLock.lock();
        try {
            TaxiThread taxiThread = allTaxis.get(taxiNumer);
            if (taxiThread != null && taxiThread.getState() != TaxiState.BROKEN) {
                availableTaxis.add(taxiNumer);
            }
        } finally {
            taxiLock.unlock();
        }

        tryAssignZlecenie(); // próbujemy nowe zadanie dla taxi
    }
}

class TaxiThread implements Runnable {
    private final Taxi taxi;
    private final DyspozytorniaWatkowa dyspozytornia;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition awariaCondition = lock.newCondition();
    private final Condition noweZlecenieCondition = lock.newCondition();

    private volatile TaxiState state = TaxiState.WAITING;

    private volatile Zlecenie currentZlecenie = null;

    TaxiThread(Taxi taxi, DyspozytorniaWatkowa dyspozytornia) {
        this.taxi = taxi;
        this.dyspozytornia = dyspozytornia;
    }

    @Override
    public void run() {
        while (!dyspozytornia.isShuttingDownDyspozytornia()) {
            // pobieramy zlecenie lub ustawiamy stan czekania na zlecenia albo naprawę awarii
            Zlecenie zlecenieToDo = getOrWaitForZlecenie();

            // wykonujemy zlecenie, jeśl zostało przydzielone
            if (zlecenieToDo != null) {
                int executedZlecenieId = zlecenieToDo.id();
                try {
                    taxi.wykonajZlecenie(executedZlecenieId);
                } finally {
                    lock.lock();
                    try {
                        if (state != TaxiState.BROKEN && state == TaxiState.RUNNING) {
                            state = TaxiState.WAITING;
                            dyspozytornia.taxiAfterZlecenie(taxi.numer(), executedZlecenieId);
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            }
        }
    }

    private Zlecenie getOrWaitForZlecenie() {
        lock.lock();
        try {
            while (state == TaxiState.BROKEN && !dyspozytornia.isShuttingDownDyspozytornia()) {
                awariaCondition.await();
            }

            if (dyspozytornia.isShuttingDownDyspozytornia()) return null;

            while (currentZlecenie == null && !dyspozytornia.isShuttingDownDyspozytornia()) {
                noweZlecenieCondition.await();
            }

            if (dyspozytornia.isShuttingDownDyspozytornia()) return null;

            if (currentZlecenie != null) {
                Zlecenie zlecenieToDo = currentZlecenie;
                currentZlecenie = null;
                state = TaxiState.RUNNING;
                return zlecenieToDo;
            }

            return null;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            lock.unlock();
        }
    }

    void assignZlecenie(Zlecenie zlecenie) {
        lock.lock();
        try {
            this.currentZlecenie = zlecenie;
            noweZlecenieCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    void markTaxiAsBroken() {
        lock.lock();
        try {
            state = TaxiState.BROKEN;
            noweZlecenieCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    void markTaxiAsRepaired() {
        lock.lock();
        try {
            state = TaxiState.WAITING;
            awariaCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    void stop() {
        lock.lock();
        try {
            awariaCondition.signal();
            noweZlecenieCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    public TaxiState getState() { return state; }

}

record Zlecenie(int id, int priority, Instant createdAt) {}
enum TaxiState { WAITING, RUNNING, BROKEN }