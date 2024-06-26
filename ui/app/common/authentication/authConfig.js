/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014-2020 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
(function () {
  'use strict';
  
  angular.module('pnc.common.authentication').provider('authConfig',
    function () {
      var ssoTokenLifespan = 10000; // 10 sec - minimal time for token validity, token is now valid for 5 min max (auth.redhat)
  
      function getSsoTokenLifespan() {
        return ssoTokenLifespan;
      }
  
      function setSsoTokenLifespan(timeInMilis) {
        if (timeInMilis > 290000){
          return; //use default value of 10 sec if config has value of more than 5 minutes
        }
        if (!angular.isNumber(timeInMilis)) {
          console.warn('Invalid value for configuration item ssoTokenLifespan: %s, using default of %d', timeInMilis, ssoTokenLifespan);
          return;
        }
        ssoTokenLifespan = timeInMilis;
      }
  
      return {
        setSsoTokenLifespan: setSsoTokenLifespan,
        getSsoTokenLifespan: getSsoTokenLifespan,
        $get: function () {
          return {
            getSsoTokenLifespan: getSsoTokenLifespan
          };
        }
      };
  
    }
  );
  
})();
