package vn.vrp.algorithm.greedy;
import vn.vrp.model.*; import java.util.*;
public final class GreedyCvrpSolver {
    private static final double EPS=1e-9;
    public Solution solve(ProblemInstance instance) {
        long started=System.nanoTime(); Set<Customer> remaining=new LinkedHashSet<>(instance.customers()); List<Route> routes=new ArrayList<>();
        for(Vehicle vehicle:instance.vehicles()) {
            if(remaining.isEmpty())break; List<Customer> selected=new ArrayList<>(); double weight=0,volume=0; long current=instance.depot().locationId();
            while(true){ final long from=current; final double usedW=weight,usedV=volume;
                Customer next=remaining.stream().filter(x->usedW+x.demandWeight()<=vehicle.capacityWeight()+EPS)
                    .filter(x->vehicle.capacityVolume()<=EPS || usedV+x.demandVolume()<=vehicle.capacityVolume()+EPS)
                    .min(Comparator.comparingDouble((Customer x)->instance.arc(from,x.locationId()).distance()).thenComparingLong(Customer::orderId)).orElse(null);
                if(next==null)break; selected.add(next);remaining.remove(next);weight+=next.demandWeight();volume+=next.demandVolume();current=next.locationId();
            }
            if(!selected.isEmpty())routes.add(buildRoute(instance,vehicle,selected));
        }
        if(!remaining.isEmpty())throw new IllegalStateException("Không đủ xe/tải trọng để phục vụ "+remaining.size()+" đơn hàng");
        double distance=routes.stream().mapToDouble(Route::totalDistance).sum(); int travel=routes.stream().mapToInt(Route::totalTravelTime).sum();
        int waiting=routes.stream().mapToInt(Route::totalWaitingTime).sum(), service=routes.stream().mapToInt(Route::totalServiceTime).sum();
        double cost=routes.stream().mapToDouble(r->r.vehicle().fixedCost()+r.totalDistance()*r.vehicle().costPerKm()+(r.totalTravelTime()+r.totalWaitingTime()+r.totalServiceTime())/60.0*r.vehicle().costPerMinute()).sum();
        return new Solution(routes,distance,travel,waiting,service,cost,(System.nanoTime()-started)/1_000_000,routes.stream().allMatch(Route::feasible));
    }
    private Route buildRoute(ProblemInstance p,Vehicle v,List<Customer> customers){
        List<RouteStop> stops=new ArrayList<>(); int time=Math.max(p.depot().openTime(),v.availableFrom()),start=time,travel=0,waiting=0,service=0; double distance=0,loadW=customers.stream().mapToDouble(Customer::demandWeight).sum(),loadV=customers.stream().mapToDouble(Customer::demandVolume).sum(),remainingW=loadW,remainingV=loadV; long at=p.depot().locationId(); boolean feasible=loadW<=v.capacityWeight()+EPS&&(v.capacityVolume()<=EPS||loadV<=v.capacityVolume()+EPS);
        stops.add(new RouteStop(RouteStop.Type.DEPOT_START,at,null,time,time,time,0,0,0,0,0,remainingW,0,remainingV,0,0,0,0));
        for(Customer x:customers){TravelArc arc=p.arc(at,x.locationId());int arrival=time+arc.travelTime(),begin=Math.max(arrival,x.readyTime()),wait=begin-arrival,depart=begin+x.serviceTime(),tw=Math.max(0,begin-x.dueTime());
            stops.add(new RouteStop(RouteStop.Type.CUSTOMER,x.locationId(),x.orderId(),arrival,begin,depart,wait,x.serviceTime(),x.demandWeight(),x.demandVolume(),remainingW,remainingW-x.demandWeight(),remainingV,remainingV-x.demandVolume(),arc.distance(),arc.travelTime(),0,tw));
            distance+=arc.distance();travel+=arc.travelTime();waiting+=wait;service+=x.serviceTime();time=depart;at=x.locationId();remainingW-=x.demandWeight();remainingV-=x.demandVolume();feasible&=tw==0;
        }
        TravelArc back=p.arc(at,p.depot().locationId());int end=time+back.travelTime();distance+=back.distance();travel+=back.travelTime();feasible&=end<=Math.min(p.depot().closeTime(),v.availableTo());
        stops.add(new RouteStop(RouteStop.Type.DEPOT_END,p.depot().locationId(),null,end,end,end,0,0,0,0,remainingW,0,remainingV,0,back.distance(),back.travelTime(),0,Math.max(0,end-p.depot().closeTime())));
        return new Route(v,customers,stops,distance,travel,waiting,service,loadW,loadV,start,end,feasible);
    }
}
