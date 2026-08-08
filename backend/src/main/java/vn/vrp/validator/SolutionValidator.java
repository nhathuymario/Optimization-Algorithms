package vn.vrp.validator;
import vn.vrp.model.*; import java.util.*; import java.util.function.Function; import java.util.stream.Collectors;
public final class SolutionValidator {
    private static final double EPS=1e-6;
    public ValidationResult validate(ProblemInstance p,Solution s){List<String> errors=new ArrayList<>();double cap=0;int tw=0;
        if(s.routes().size()>p.vehicles().size())errors.add("Số route vượt số xe hiện có");
        Map<Long,Long> visits=s.routes().stream().flatMap(r->r.customers().stream()).map(Customer::orderId).collect(Collectors.groupingBy(Function.identity(),Collectors.counting()));
        Set<Long> expected=p.customers().stream().map(Customer::orderId).collect(Collectors.toSet());
        for(long id:expected)if(visits.getOrDefault(id,0L)!=1)errors.add("Order "+id+" được phục vụ "+visits.getOrDefault(id,0L)+" lần");
        for(long id:visits.keySet())if(!expected.contains(id))errors.add("Order không thuộc dataset: "+id);
        Set<Long> vehicles=new HashSet<>();
        for(Route r:s.routes()){if(!vehicles.add(r.vehicle().id()))errors.add("Xe "+r.vehicle().code()+" được dùng nhiều route");double cv=Math.max(0,r.totalLoadWeight()-r.vehicle().capacityWeight());if(r.vehicle().capacityVolume()>EPS)cv+=Math.max(0,r.totalLoadVolume()-r.vehicle().capacityVolume());cap+=cv;if(cv>EPS)errors.add("Route xe "+r.vehicle().code()+" vượt tải");
            if(r.stops().size()<2||r.stops().getFirst().type()!=RouteStop.Type.DEPOT_START||r.stops().getLast().type()!=RouteStop.Type.DEPOT_END)errors.add("Route xe "+r.vehicle().code()+" không bắt đầu/kết thúc tại depot");
            double calculated=0;int routeTw=0;for(int i=1;i<r.stops().size();i++){RouteStop prev=r.stops().get(i-1),cur=r.stops().get(i);calculated+=p.arc(prev.locationId(),cur.locationId()).distance();routeTw+=cur.timeWindowViolation();}tw+=routeTw;if(routeTw>0)errors.add("Route xe "+r.vehicle().code()+" vi phạm time window "+routeTw+" giây");if(Math.abs(calculated-r.totalDistance())>EPS)errors.add("Sai tổng khoảng cách route xe "+r.vehicle().code());
        }
        int unserved=(int)expected.stream().filter(id->visits.getOrDefault(id,0L)==0).count();return new ValidationResult(errors.isEmpty(),errors,unserved,cap,tw);
    }
}
