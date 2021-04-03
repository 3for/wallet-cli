package org.tron.common.parameter;

import lombok.Getter;
import lombok.Setter;
import com.beust.jcommander.Parameter;
import org.tron.core.Constant;

public class CommonParameter {

  public static CommonParameter PARAMETER = new CommonParameter();

  @Getter
  @Setter
  @Parameter(names = {"--support-constant"})
  public boolean supportConstant = false;

  @Getter
  @Setter
  public String cryptoEngine = Constant.ECKey_ENGINE;

  public static CommonParameter getInstance() {
    return PARAMETER;
  }

  public boolean isECKeyCryptoEngine() {

    return cryptoEngine.equalsIgnoreCase(Constant.ECKey_ENGINE);
  }
}
