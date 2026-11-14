import org.minviewer.app.PdfGradientCompatibility;
import com.tom_roush.pdfbox.cos.*;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.common.function.PDFunction;
import java.io.File;
import java.util.*;

public class GradientPdfProbe {
    static List<PDFunction> functions(PDDocument document) throws Exception {
        List<PDFunction> result=new ArrayList<>();
        for(COSObject item:document.getDocument().getObjects()) {
            if(!(item.getObject() instanceof COSDictionary))continue;
            COSDictionary pattern=(COSDictionary)item.getObject();
            if(pattern.getInt(COSName.getPDFName("PatternType"))!=2)continue;
            COSDictionary shading=(COSDictionary)pattern.getDictionaryObject(COSName.getPDFName("Shading"));
            result.add(PDFunction.create(shading.getDictionaryObject(COSName.getPDFName("Function"))));
        }
        return result;
    }
    public static void main(String[] args) throws Exception {
        File source=new File(args[0]),target=new File(args[1]);
        PdfGradientCompatibility.INSTANCE.normalize(source,target);
        try(PDDocument before=PDDocument.load(source);PDDocument after=PDDocument.load(target)) {
            List<PDFunction> a=functions(before),b=functions(after);
            if(a.isEmpty()||a.size()!=b.size())throw new AssertionError("gradient count changed");
            for(int i=0;i<a.size();i++)for(int step=0;step<=1000;step++) {
                float[] x=a.get(i).eval(new float[]{step/1000f}),y=b.get(i).eval(new float[]{step/1000f});
                if(x.length!=y.length)throw new AssertionError("color channels changed");
                for(int j=0;j<x.length;j++)if(Math.abs(x[j]-y[j])>.00001f)throw new AssertionError("gradient color changed");
            }
        }
        System.out.println("GRADIENT_1001_POINTS_PER_PATTERN_COLOR_EQUIVALENCE_PASS");
    }
}
