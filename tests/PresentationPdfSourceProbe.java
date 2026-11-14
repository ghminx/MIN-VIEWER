import java.io.File;
import org.minviewer.app.PresentationPdfSource;

/** 실제 PPTX 파일 변환 경계의 결과를 호스트 품질 시험에 제공. */
public class PresentationPdfSourceProbe {
    public static void main(String[] args) throws Exception {
        System.out.println(PresentationPdfSource.INSTANCE.create(new File(args[0]), new File(args[1]), () -> false));
    }
}
