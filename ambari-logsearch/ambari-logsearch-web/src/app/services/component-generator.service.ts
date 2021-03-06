/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {Injectable, ComponentFactoryResolver, ViewContainerRef} from '@angular/core';
import {HostsService} from '@app/services/storage/hosts.service';
import {LogsContainerService} from '@app/services/logs-container.service';
import {NodeBarComponent} from '@app/components/node-bar/node-bar.component';

@Injectable()
export class ComponentGeneratorService {

  constructor(private resolver: ComponentFactoryResolver, private hostsStorage: HostsService, private logsContainer: LogsContainerService) {
  }

  private createComponent(type: any, container: ViewContainerRef, properties?: any): void {
    const factory = this.resolver.resolveComponentFactory(type);
    container.clear();
    let component = container.createComponent(factory);
    Object.assign(component.instance, properties);
  }

  getDataForHostsNodeBar(hostName: string, container: ViewContainerRef): void {
    let data;
    this.hostsStorage.getAll().subscribe(hosts => {
      if (container && hosts && hosts.length) {
        const selectedHost = hosts.find(host => host.name === hostName);
        data = selectedHost.logLevelCount.map(event => {
          return {
            color: this.logsContainer.colors[event.name],
            value: event.value
          };
        });
        if (data.length) {
          this.createComponent(NodeBarComponent, container, {
            data
          });
        }
      }
    });
  }

}
