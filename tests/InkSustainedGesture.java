import android.os.SystemClock;
import android.view.*;
import java.lang.reflect.Method;
import java.util.*;

/** 실제 화면에 지속적으로 펜 이벤트를 주입하고 처리 지연을 기록. */
public class InkSustainedGesture {
    public static void main(String[] args)throws Exception {
        long duration=Long.parseLong(args[0]),started=SystemClock.uptimeMillis(),report=started;
        float left=args.length>1?Float.parseFloat(args[1]):400;
        float top=args.length>2?Float.parseFloat(args[2]):330;
        float width=args.length>3?Float.parseFloat(args[3]):315;
        float height=args.length>4?Float.parseFloat(args[4]):192;
        Class<?> cls=Class.forName("android.hardware.input.InputManagerGlobal");
        Object manager=cls.getMethod("getInstance").invoke(null);
        Method inject=cls.getMethod("injectInputEvent",InputEvent.class,int.class);
        MotionEvent.PointerProperties property=new MotionEvent.PointerProperties();property.id=0;property.toolType=MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords point=new MotionEvent.PointerCoords();point.pressure=.7f;point.size=1;
        List<Long> delays=new ArrayList<>();int strokes=0;
        while(SystemClock.uptimeMillis()-started<duration) {
            long down=SystemClock.uptimeMillis();
            for(int step=0;step<=9;step++) {
                point.x=left+step*width/9;point.y=top+(strokes%16)*height/16+(float)Math.sin(step*.6)*(args.length>4?height*.07f:14);
                int action=step==0?MotionEvent.ACTION_DOWN:step==9?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;
                MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{property},new MotionEvent.PointerCoords[]{point},0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);
                long before=SystemClock.uptimeMillis();
                if(!(Boolean)inject.invoke(manager,event,2))throw new IllegalStateException("펜 입력 실패");
                delays.add(SystemClock.uptimeMillis()-before);event.recycle();SystemClock.sleep(20);
            }
            strokes++;SystemClock.sleep(100);
            if(SystemClock.uptimeMillis()-report>=60000){System.out.println("PROGRESS elapsedMs="+(SystemClock.uptimeMillis()-started)+" strokes="+strokes);System.out.flush();report=SystemClock.uptimeMillis();}
        }
        Collections.sort(delays);
        System.out.println("FINISHED elapsedMs="+(SystemClock.uptimeMillis()-started)+" strokes="+strokes+" events="+delays.size()+" injectP50Ms="+delays.get(delays.size()/2)+" injectP95Ms="+delays.get((int)(delays.size()*.95))+" injectMaxMs="+delays.get(delays.size()-1));
    }
}
