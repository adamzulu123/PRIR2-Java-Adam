import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class DyspozytorniaWatkowa implements Dyspozytornia {
    // Zlecenia
    private final AtomicInteger zlecenieId = new AtomicInteger(0);
    private final Comparator<Zlecenie> zlecenieComparator = Comparator
            .comparing(Zlecenie::priority).reversed()
            .thenComparing(Zlecenie::createdAt);
    private final PriorityBlockingQueue<Zlecenie> zlecenieQueue = new PriorityBlockingQueue<>(100, zlecenieComparator);
    private final ConcurrentHashMap<Integer, Integer> assignedOrders = new ConcurrentHashMap<>();

    // Taxi
    private final ConcurrentHashMap<Integer, TaxiThread> allTaxis = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Integer> availableTaxis = new ConcurrentLinkedQueue<>();
    private final ReentrantLock taxiLock = new ReentrantLock();
    private Integer brokenTaxiId = null;

    // Dyspozytornia
    private volatile boolean shuttingDownDyspozytornia = false;

    @Override
    public void flota(Set<Taxi> flota) {
        for (Taxi taxi : flota) {
            TaxiThread taxiThread = new TaxiThread(taxi, this);
            allTaxis.put(taxi.numer(), taxiThread);
            availableTaxis.offer(taxi.numer());
            Thread thread = new Thread(taxiThread);
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    public int zlecenie() {
        if (shuttingDownDyspozytornia)
            throw new IllegalStateException("Dyspozytornia is shutting down");

        int id = zlecenieId.incrementAndGet();
        Zlecenie zlecenie = new Zlecenie(id, 0, Instant.now());
        zlecenieQueue.offer(zlecenie);
        tryAssignZlecenie();
        return id;
    }

    @Override
    public void awaria(int numer, int numerZlecenia) {
        taxiLock.lock();
        try {
            TaxiThread taxiThread = allTaxis.get(numer);
            if (taxiThread == null) throw new RuntimeException("Taxi thread not found");
            if (taxiThread.getState() == TaxiState.BROKEN)
                throw new IllegalStateException("Broken Taxi cannot be broken again");
            if (brokenTaxiId != null)
                throw new IllegalStateException("Two taxi cannot be broken at the same time");

            Integer wasAssigned = assignedOrders.get(numerZlecenia);
            if (wasAssigned == null) {
                throw new IllegalArgumentException("Order: " + numerZlecenia + " - was not assigned to any taxi");
            }
            if (!wasAssigned.equals(numer)) {
                throw new IllegalStateException("Order: " + numerZlecenia + " - was assigned to different taxi");
            }

            brokenTaxiId = numer;
            assignedOrders.remove(numerZlecenia);

            Zlecenie zlecenie = new Zlecenie(numerZlecenia, 1, Instant.now());
            zlecenieQueue.offer(zlecenie);

            taxiThread.markTaxiAsBroken();

        } finally {
            taxiLock.unlock();
        }

        tryAssignZlecenie();
    }

    @Override
    public void naprawiono(int numer) {
        taxiLock.lock();
        try {
            TaxiThread taxiThread = allTaxis.get(numer);
            if (taxiThread == null) throw new IllegalStateException("Taxi thread not found");
            if (taxiThread.getState() != TaxiState.BROKEN)
                throw new IllegalStateException("Taxi not broken");

            brokenTaxiId = null;
            taxiThread.markTaxiAsRepaired();
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
            int skippingBrokenTaxi = 0; // aby uniknać nieskończonej pętli

            while (!zlecenieQueue.isEmpty() && !availableTaxis.isEmpty()) {
                Integer taxiNumer = availableTaxis.poll();
                if (taxiNumer == null) break;
                // jak zepsuta dodajemy ja na koniec kolejki
                if (taxiNumer.equals(brokenTaxiId)) {
                    availableTaxis.offer(taxiNumer);
                    skippingBrokenTaxi++;

                    if (skippingBrokenTaxi > 1) break;
                    continue;
                }

                skippingBrokenTaxi = 0;

                Zlecenie zlecenie = zlecenieQueue.poll();
                if (zlecenie == null) {
                    availableTaxis.offer(taxiNumer);
                    break;
                }

                TaxiThread taxiThread = allTaxis.get(taxiNumer);
                if (taxiThread != null && taxiThread.getState() != TaxiState.BROKEN) {
                    assignedOrders.put(zlecenie.id(), taxiNumer);
                    taxiThread.assignZlecenie(zlecenie);
                } else {
                    zlecenieQueue.offer(zlecenie);
                    availableTaxis.offer(taxiNumer);
                }
            }
        } finally {
            taxiLock.unlock();
        }
    }

    void taxiAfterZlecenie(int taxiNumer, int zlecenieId) {
        if (shuttingDownDyspozytornia) return;

        taxiLock.lock();
        try {
            assignedOrders.remove(zlecenieId);

            TaxiThread taxiThread = allTaxis.get(taxiNumer);
            if (taxiThread != null && taxiThread.getState() != TaxiState.BROKEN) {
                availableTaxis.offer(taxiNumer);
            }
        } finally {
            taxiLock.unlock();
        }

        tryAssignZlecenie();
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
            Zlecenie zlecenieToDo = getOrWaitForZlecenie();

            // wykonaj zlecenie
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