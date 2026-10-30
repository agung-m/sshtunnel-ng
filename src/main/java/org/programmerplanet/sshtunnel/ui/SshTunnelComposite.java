/*
 * Copyright 2023 Mulya Agung
 * Copyright 2009 Joseph Fifield
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.programmerplanet.sshtunnel.ui;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.MenuDetectEvent;
import org.eclipse.swt.events.MenuDetectListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.ShellAdapter;
import org.eclipse.swt.events.ShellEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolTip;
import org.eclipse.swt.widgets.Tray;
import org.eclipse.swt.widgets.TrayItem;
import org.programmerplanet.sshtunnel.model.Configuration;
import org.programmerplanet.sshtunnel.model.ConnectionException;
import org.programmerplanet.sshtunnel.model.ConnectionManager;
import org.programmerplanet.sshtunnel.model.Session;

/**
 * 
 * @author <a href="agungm@outlook.com">Mulya Agung</a>
 * @author <a href="jfifield@programmerplanet.org">Joseph Fifield</a>
 */
public class SshTunnelComposite extends Composite {

	public static final String APPLICATION_TITLE = "SSH Tunnel NG";
	private static final String APPLICATION_VERSION = "v0.8";
	private static final String APPLICATION_SITE = "github.com/agung-m/sshtunnel-ng";
	private static final String APPLICATION_IMAGE_PATH = "/images/sshtunnel-ng.png";
	// Same logo with the big circle green (connected) or red (reconnecting a lost session)
	private static final String APPLICATION_CONNECTED_IMAGE_PATH = "/images/sshtunnel-ng-connected.png";
	private static final String APPLICATION_RECONNECTING_IMAGE_PATH = "/images/sshtunnel-ng-reconnecting.png";
	private static final String CONNECT_IMAGE_PATH = "/images/connect.png";
	private static final String DISCONNECT_IMAGE_PATH = "/images/disconnect.png";
	private static final String CONNECT_ALL_IMAGE_PATH = "/images/connect_all.png";
	private static final String DISCONNECT_ALL_IMAGE_PATH = "/images/disconnect_all.png";
	private static final String CONNECTED_IMAGE_PATH = "/images/bullet_green.png";
	private static final String DISCONNECTED_IMAGE_PATH = "/images/bullet_red.png";

	private Image applicationImage;
	private Image connectImage;
	private Image disconnectImage;
	private Image connectAllImage;
	private Image disconnectAllImage;
	private Image connectedImage;
	private Image disconnectedImage;
	// Application icon showing the connection status, for the taskbar and the tray
	private Image appConnectedImage;
	private Image appReconnectingImage;
	private Image currentAppImage;

	private Button connectButton;
	private Button disconnectButton;

	private Button connectAllButton;
	private Button disconnectAllButton;
	private TrayItem trayItem;
	
	private Label versionLabel;
	private Label siteLabel;
	private ProgressBar progressBar;
	private boolean isConnecting;

	private Configuration configuration;
	private SashForm sashForm;
	private SessionsComposite sessionsComposite;
	private TunnelsComposite tunnelsComposite;
	private Composite statusBarComposite;

	private Session currentSession = null;

	private Shell shell;

	public SshTunnelComposite(Shell shell) {
		super(shell, SWT.NONE);
		this.shell = shell;
		load();
		initialize();
	}

	private void load() {
		configuration = new Configuration();
		try {
			configuration.read();
		} catch (IOException e) {
			MessageBox messageBox = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
			messageBox.setText("Error");
			messageBox.setMessage("Unable to load configuration.");
			messageBox.open();
		}
	}

	private void save() {
		configuration.setLeft(shell.getBounds().x);
		configuration.setTop(shell.getBounds().y);
		configuration.setWidth(shell.getBounds().width);
		configuration.setHeight(shell.getBounds().height);
		configuration.setWeights(sashForm.getWeights());
		try {
			configuration.write();
		} catch (IOException e) {
			MessageBox messageBox = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
			messageBox.setText("Error");
			messageBox.setMessage("Unable to save configuration.");
			messageBox.open();
		}
	}

	private void initialize() {
		createImages();

		shell.setText(APPLICATION_TITLE);
		shell.setLayout(new FillLayout());
		shell.setImage(applicationImage);
		shell.addShellListener(new ShellAdapter() {
			public void shellClosed(ShellEvent e) {
				exit();
			}

			public void shellIconified(ShellEvent e) {
				//shell.setVisible(false);
				shell.setMinimized(true);
			}
		});

		this.setLayout(new GridLayout());

		createSashForm();
		createSessionsComposite();
		createTunnelsComposite();
		createButtonBarComposite();
		createStatusBarComposite();
		createTrayIcon();

		shell.setBounds(configuration.getLeft(), configuration.getTop(), configuration.getWidth(), configuration.getHeight());
		sashForm.setWeights(configuration.getWeights());
		
		// Run connection monitor
		SessionConnectionMonitor.getInstance().setSshTunnelComposite(this);
		SessionConnectionMonitor.getInstance().startMonitor();
		//ConnectionMonitor.getInstance().setThreadStopped(false);
		//Display.getDefault().asyncExec(ConnectionMonitor.getInstance());
	}

	private void createImages() {
		applicationImage = loadImage(APPLICATION_IMAGE_PATH);
		connectImage = loadImage(CONNECT_IMAGE_PATH);
		disconnectImage = loadImage(DISCONNECT_IMAGE_PATH);
		connectAllImage = loadImage(CONNECT_ALL_IMAGE_PATH);
		disconnectAllImage = loadImage(DISCONNECT_ALL_IMAGE_PATH);
		connectedImage = loadImage(CONNECTED_IMAGE_PATH);
		disconnectedImage = loadImage(DISCONNECTED_IMAGE_PATH);
		appConnectedImage = loadImage(APPLICATION_CONNECTED_IMAGE_PATH);
		appReconnectingImage = loadImage(APPLICATION_RECONNECTING_IMAGE_PATH);
		currentAppImage = applicationImage;
	}

	/**
	 * Green logo while any session is connected, red logo while a lost session is being reconnected
	 * (takes precedence), normal logo otherwise.
	 */
	private void updateStatusImage() {
		Image image = applicationImage;
		if (SessionConnectionMonitor.getInstance().isReconnectingAny()) {
			image = appReconnectingImage;
		} else if (anyConnectedSessions()) {
			image = appConnectedImage;
		}
		if (image != currentAppImage) {
			currentAppImage = image;
			shell.setImage(image);
			if (trayItem != null) {
				trayItem.setImage(image);
			}
		}
	}

	private Image loadImage(String path) {
		InputStream stream = SshTunnelComposite.class.getResourceAsStream(path);
		try {
			return new Image(this.getDisplay(), stream);
		} finally {
			try {
				stream.close();
			} catch (IOException e) {
				// ignore
			}
		}
	}

	private void createSashForm() {
		sashForm = new SashForm(this, SWT.VERTICAL);

		GridData gridData = new GridData();
		gridData.grabExcessHorizontalSpace = true;
		gridData.horizontalAlignment = GridData.FILL;
		gridData.grabExcessVerticalSpace = true;
		gridData.verticalAlignment = GridData.FILL;
		sashForm.setLayoutData(gridData);
	}

	private void createSessionsComposite() {
		SessionChangeListener sessionChangeListener = new SessionChangeAdapter() {

			public void sessionAdded(Session session) {
				updateConnectButtons();
			}

			public void sessionRemoved(Session session) {
				SessionConnectionMonitor.getInstance().cancelReconnect(session);
				updateConnectButtons();
			}

			public void sessionSelectionChanged(Session session) {
				SshTunnelComposite.this.currentSession = session;
				tunnelsComposite.setSession(session);
				// Check session alive status
//				Exception err = ConnectionManager.getInstance().getSessionException(session);
//				if (err != null) {
//					ConnectionManager.getInstance().disconnect(session);
//					showErrorMessage("Connection error", err);
//				}
				updateConnectButtons();
			}

		};
		sessionsComposite = new SessionsComposite(sashForm, SWT.NONE, configuration.getSessions(), sessionChangeListener);
	}

	private void createTunnelsComposite() {
		TunnelChangeListener tunnelChangeListener = new TunnelChangeAdapter();
		//tunnelsComposite = new TunnelsComposite(sashForm, SWT.NONE, tunnelChangeListener);
		tunnelsComposite = new TunnelsComposite(sashForm, shell, SWT.NONE, tunnelChangeListener);
	}

	private void createButtonBarComposite() {
		Composite buttonBarComposite = new Composite(this, SWT.NONE);
		buttonBarComposite.setLayout(new FillLayout());

		GridData gridData = new GridData();
		gridData.horizontalAlignment = GridData.CENTER;
		buttonBarComposite.setLayoutData(gridData);

		connectButton = new Button(buttonBarComposite, SWT.PUSH);
		connectButton.setText("Connect");
		connectButton.setToolTipText("Connect");
		connectButton.setImage(connectImage);
		connectButton.setEnabled(false);

		disconnectButton = new Button(buttonBarComposite, SWT.PUSH);
		disconnectButton.setText("Disconnect");
		disconnectButton.setToolTipText("Disconnect");
		disconnectButton.setImage(disconnectImage);
		disconnectButton.setEnabled(false);

		connectAllButton = new Button(buttonBarComposite, SWT.PUSH);
		connectAllButton.setText("Connect All");
		connectAllButton.setToolTipText("Connect All");
		connectAllButton.setImage(connectAllImage);
		connectAllButton.setEnabled(true);

		disconnectAllButton = new Button(buttonBarComposite, SWT.PUSH);
		disconnectAllButton.setText("Disconnect All");
		disconnectAllButton.setToolTipText("Disconnect All");
		disconnectAllButton.setImage(disconnectAllImage);
		disconnectAllButton.setEnabled(false);
		
		connectButton.addSelectionListener(new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				Display display = SshTunnelComposite.this.getDisplay();
				Runnable runnable = new Runnable() {
					public void run() {
						connect();
					}
				};
				display.asyncExec(runnable);
			}
		});

		disconnectButton.addSelectionListener(new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				Display display = SshTunnelComposite.this.getDisplay();
				Runnable runnable = new Runnable() {
					public void run() {
						disconnect();
					}
				};
				display.asyncExec(runnable);
			}
		});

		connectAllButton.addSelectionListener(new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				Display display = SshTunnelComposite.this.getDisplay();
				Runnable runnable = new Runnable() {
					public void run() {
						connectAll();
					}
				};
				display.asyncExec(runnable);
			}
		});

		disconnectAllButton.addSelectionListener(new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				Display display = SshTunnelComposite.this.getDisplay();
				Runnable runnable = new Runnable() {
					public void run() {
						disconnectAll();
					}
				};
				display.asyncExec(runnable);
			}
		});
	}
	
	public void createStatusBarComposite() {
		//Composite statusLineComposite = new Composite(this, SWT.NONE);
		statusBarComposite = new Composite(this, SWT.NONE);
		
//		FillLayout fillLayout = new FillLayout();
//		fillLayout.marginHeight = 0;
//		fillLayout.marginWidth = 8;
		//statusLineComposite.setLayout(fillLayout);
//		GridLayout twoColsGridLayout = new GridLayout(2, false);
//		twoColsGridLayout.marginRight = 2;
//		twoColsGridLayout.marginTop = 0;
		
		GridLayout statusBarGridLayout = new GridLayout(3, false);
		statusBarGridLayout.marginRight = 2;
		statusBarGridLayout.marginTop = 0;
		//statusBarGridLayout.horizontalSpacing = 2;
		
		//statusBarComposite.setLayout(twoColsGridLayout);
		statusBarComposite.setLayout(statusBarGridLayout);
		statusBarComposite.setLayoutData(new GridData(SWT.FILL, SWT.END, true, false));

//		GridData gridData = new GridData(SWT.BEGINNING, SWT.CENTER, true, false);
//		gridData.horizontalAlignment = GridData.END;
//		statusLineComposite.setLayoutData(gridData);
//		gridData.widthHint = 100;
		
		siteLabel = new Label(statusBarComposite, SWT.NONE);
		siteLabel.setText(APPLICATION_SITE);
		siteLabel.setEnabled(false);
		siteLabel.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, true, false));
		
		progressBar = new ProgressBar(statusBarComposite, SWT.INDETERMINATE);
	    progressBar.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
	    //progressBar.setMaximum(10);
	    progressBar.setVisible(false);
		
		versionLabel = new Label(statusBarComposite, SWT.NONE);
		//versionLabel.setText(APPLICATION_SITE + " | " + APPLICATION_VERSION);
		versionLabel.setText("   " + APPLICATION_VERSION);
		versionLabel.setEnabled(false);
		//versionLabel.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
		versionLabel.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
	}
	
	private void showProgressBar() {
		connectButton.setEnabled(false);
		connectAllButton.setEnabled(false);
		disconnectAllButton.setEnabled(false);
		progressBar.setVisible(true);
		
//		Display.getDefault().asyncExec(new Runnable() {
//            @Override
//            public void run() {
//            	progressBar.setVisible(true);
//            }
//		});
	}
	
	private void stopProgressBar() {
		//if (!progressBar.isDisposed()) {
		progressBar.setVisible(false);
//			Display.getDefault().asyncExec(new Runnable() {
//	            @Override
//	            public void run() {
//	            	progressBar.setVisible(false);
//	            }
//			});
		//}
	}
	
	private void updateProgressBar() {
	    new Thread(new Runnable()
	    {
	        //private int                 progress    = 0;
	        //private static final int    INCREMENT   = 25;
	    	//ProgressBar progressBar = SshTunnelComposite.this.progressBar;

	        @Override
	        public void run() {
	        	while (!progressBar.isDisposed() && isConnecting) {
		        	Display.getDefault().asyncExec(new Runnable() {
	                    @Override
	                    public void run() {
//	                        if (!progressBar.isDisposed())
//	                            progressBar.setSelection((progress += INCREMENT) % (progressBar.getMaximum() + INCREMENT));
//	                        progressBar = new ProgressBar(statusBarComposite, SWT.INDETERMINATE);
//	        	    	    progressBar.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, true, false));
//	        	    	    progressBar.setMaximum(100);
	                    	//if (isConnecting)
	                    	progressBar.setVisible(true);
	                    	//else
	                    	//	progressBar.setVisible(false);
	                    }
	                });
		        	
			        try {
			        	Thread.sleep(1000);
		            }
	                catch (InterruptedException e) {
	                    e.printStackTrace();
	                }
	        	}
	        	Display.getDefault().asyncExec(new Runnable() {
		    		 @Override
		    		 public void run() {
		    			 progressBar.setVisible(false);
				 	}
	        	});
	        	
	        }
//	            while (!progressBar.isDisposed()) {
//	                Display.getDefault().asyncExec(new Runnable() {
//	                    @Override
//	                    public void run() {
//	                        if (!progressBar.isDisposed())
//	                            progressBar.setSelection((progress += INCREMENT) % (progressBar.getMaximum() + INCREMENT));
//	                    }
//	                });
//
//	                try {
//	                    Thread.sleep(1000);
//	                }
//	                catch (InterruptedException e) {
//	                    e.printStackTrace();
//	                }
//	            }
//	        }
	    }).start();
	}
	
//	private void stopProgressBar() {
//		if (progressBar.isDisposed())
//			return;
//		else {
//			isConnecting = false;
//			//progressBar.setVisible(false);
//		}
//			
//	}
	
	public void connectionStatusChanged() {
		sessionsComposite.updateTable();
		tunnelsComposite.updateTable();
		updateConnectButtons();
		updateStatusImage();
		stopProgressBar();
	}

	private void updateConnectButtons() {
		connectButton.setEnabled(currentSession != null ? !ConnectionManager.getInstance().isConnected(currentSession) : false);
		// Disconnect also stops the automatic reconnect of a lost session
		disconnectButton.setEnabled(currentSession != null ? ConnectionManager.getInstance().isConnected(currentSession)
				|| SessionConnectionMonitor.getInstance().isReconnecting(currentSession) : false);
		connectAllButton.setEnabled(anyDisconnectedSessions());
		disconnectAllButton.setEnabled(anyConnectedSessions() || SessionConnectionMonitor.getInstance().isReconnectingAny());
	}

	private boolean anyDisconnectedSessions() {
		boolean result = false;
		for (Iterator<Session> i = configuration.getSessions().iterator(); i.hasNext();) {
			Session session = i.next();
			if (!ConnectionManager.getInstance().isConnected(session)) {
				result = true;
				break;
			}
		}
		return result;
	}

	private boolean anyConnectedSessions() {
		boolean result = false;
		for (Iterator<Session> i = configuration.getSessions().iterator(); i.hasNext();) {
			Session session = i.next();
			if (ConnectionManager.getInstance().isConnected(session)) {
				result = true;
				break;
			}
		}
		return result;
	}

	private void connect() {
		connect(currentSession);
		
	}

	private void connect(Session session) {
		save();
		if (session != null && !ConnectionManager.getInstance().isConnected(session)) {
//			try {
//				ConnectionManager.getInstance().connect(session, shell);
//				// Put to monitored list
//				SessionConnectionMonitor.getInstance().addSession(session.getSessionName(), session);
//			} catch (ConnectionException ce) {
//				try {
//					ConnectionManager.getInstance().disconnect(session);
//				} catch (Exception e) {
//				}
//				showErrorMessage("Unable to connect to '" + session.getSessionName() + "'", ce);
//			}
			
			//isConnecting = true;
			//updateProgressBar();
			showProgressBar();
			
			new Thread(new Runnable() {
				@Override
				public void run() {
						try {
						ConnectionManager.getInstance().connect(session, shell);
					} catch (ConnectionException ce) {
						try {
							ConnectionManager.getInstance().disconnect(session);
						} catch (Exception e) {
							
						}
						Display.getDefault().asyncExec(new Runnable() {
		                    @Override
		                    public void run() {
		                    	showErrorMessage("Unable to connect to '" + session.getSessionName() + "'", ce);
		                    }
						});
					}
						
					Display.getDefault().asyncExec(new Runnable() {
	                    @Override
	                    public void run() {
	                    	connectionStatusChanged();
	                    }
					});
				}
		    }).start();
		}
		//connectionStatusChanged();
	}

	private void showErrorMessage(String message, Exception e) {
		Throwable cause = getOriginatingCause(e);
		MessageBox messageBox = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
		messageBox.setText("Error");
		messageBox.setMessage(message + ": " + cause.getMessage());
		messageBox.open();
	}
	
	public void showDisconnectedMessage(Session session) {
//		MessageBox messageBox = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
//		messageBox.setText("Error");
//		messageBox.setMessage("Lost connection to " + session.getSessionName() + 
//				" (" + session.getHostname() + ")");
//		messageBox.open();
		showTrayMessage(session, SWT.ICON_ERROR, "Connection to " + session.getHostname() + " has been lost. Reconnecting automatically...");
	}

	public void showReconnectedMessage(Session session) {
		showTrayMessage(session, SWT.ICON_INFORMATION, "Connection to " + session.getHostname() + " has been restored.");
	}

	public void showReconnectFailedMessage(Session session, String reason) {
		showTrayMessage(session, SWT.ICON_ERROR, "Stopped reconnecting to " + session.getHostname() + ": " + reason);
	}

	private void showTrayMessage(Session session, int icon, String message) {
		if (trayItem != null && trayItem.getVisible()) {
			ToolTip tip = new ToolTip(shell, SWT.BALLOON | icon);
			//Rectangle trayRect = Display.getCurrent().getBounds();
			//System.out.println(getLocation() + "," + trayRect);
			//tip.setLocation(trayRect.width, trayRect.height-100);
			tip.setText("Session: " + session.getSessionName());
			tip.setMessage(message);
			trayItem.setToolTip(tip);
			tip.setVisible(true);
		}
	}

	private Throwable getOriginatingCause(Exception e) {
		Throwable cause = e;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause;
	}

	private void disconnect() {
		disconnect(currentSession);
	}

	private void disconnect(Session session) {
		save();
		if (session != null) {
			SessionConnectionMonitor.getInstance().cancelReconnect(session);
			// Also when the connection has already died, so that its JSch session is not reused
			ConnectionManager.getInstance().disconnect(session);
		}
		connectionStatusChanged();
	}

	private void connectAll() {
		save();
//		Session sessionToConnect = null;
//		try {
//			for (Iterator<Session> i = configuration.getSessions().iterator(); i.hasNext();) {
//				sessionToConnect = i.next();
//				if (!ConnectionManager.getInstance().isConnected(sessionToConnect)) {
//					ConnectionManager.getInstance().connect(sessionToConnect, shell);
//				}
//			}
//		} catch (ConnectionException ce) {
//			for (Iterator<Session> i = configuration.getSessions().iterator(); i.hasNext();) {
//				Session session = i.next();
//				try {
//					ConnectionManager.getInstance().disconnect(session);
//				} catch (Exception e) {
//				}
//			}
//			showErrorMessage("Unable to connect to '" + sessionToConnect.getSessionName() + "'", ce);
//		}
//		connectionStatusChanged();
		showProgressBar();
		
		new Thread(new Runnable() {
			@Override
			public void run() {
				Session sessionToConnect = null;
				try {
					for (Iterator<Session> i = configuration.getSessions().iterator(); i.hasNext();) {
						sessionToConnect = i.next();
						if (!ConnectionManager.getInstance().isConnected(sessionToConnect)) {
							ConnectionManager.getInstance().connect(sessionToConnect, shell);
						}
					}
				} catch (ConnectionException ce) {
					for (Iterator<Session> i = configuration.getSessions().iterator(); i.hasNext();) {
						Session session = i.next();
						try {
							ConnectionManager.getInstance().disconnect(session);
						} catch (Exception e) {
						}
					}
					
					final Session failedSession = sessionToConnect;
					Display.getDefault().asyncExec(new Runnable() {
	                    @Override
	                    public void run() {
	                    	showErrorMessage("Unable to connect to '" + failedSession.getSessionName() + "'", ce);
	                    }
					});
				}
					
				Display.getDefault().asyncExec(new Runnable() {
                    @Override
                    public void run() {
                    	connectionStatusChanged();
                    }
				});
			}
	    }).start();
	}

	private void disconnectAll() {
		save();
		for (Iterator<Session> i = configuration.getSessions().iterator(); i.hasNext();) {
			Session session = i.next();
			SessionConnectionMonitor.getInstance().cancelReconnect(session);
			ConnectionManager.getInstance().disconnect(session);
		}
		connectionStatusChanged();
	}

	private void createTrayIcon() {
		Tray tray = this.getDisplay().getSystemTray();

		//final TrayItem trayItem = new TrayItem(tray, 0);
		trayItem = new TrayItem(tray, 0);
		trayItem.setToolTipText(APPLICATION_TITLE);
		trayItem.setImage(applicationImage);
		
		trayItem.addSelectionListener(new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				//shell.setVisible(!shell.isVisible());
				shell.setMinimized(!shell.getMinimized());
			}
		});

		trayItem.addMenuDetectListener(new MenuDetectListener() {
			public void menuDetected(MenuDetectEvent e) {
				Shell s = new Shell(e.display);
				Menu m = new Menu(s, SWT.POP_UP);

				// build menu
				Listener menuItemListener = new Listener() {
					public void handleEvent(Event event) {
						Object source = event.widget.getData();
						if (source instanceof Session) {
							final Session session = (Session) source;
							Runnable runnable = new Runnable() {
								public void run() {
									boolean connectedBefore = ConnectionManager.getInstance().isConnected(session);
									if (!connectedBefore) {
										connect(session);
									} else if (connectedBefore) {
										disconnect(session);
									}
									boolean connectedAfter = ConnectionManager.getInstance().isConnected(session);
									if (connectedBefore != connectedAfter) {
										String title = "Session: " + session.getSessionName();
										String message = session.getSessionName() + " is now " + (connectedAfter ? "connected" : "disconnected") + ".";
										ToolTip tip = new ToolTip(shell, SWT.BALLOON | SWT.ICON_INFORMATION);
										tip.setText(title);
										tip.setMessage(message);
										trayItem.setToolTip(tip);
										tip.setVisible(true);
									}
								}
							};
							event.display.asyncExec(runnable);
						}
					}
				};

				for (Iterator<Session> i = configuration.getSessions().iterator(); i.hasNext();) {
					Session session = i.next();
					MenuItem menuItem = new MenuItem(m, SWT.NONE);
					// set session
					menuItem.setData(session);
					// set text
					String text = session.getSessionName();
					menuItem.setText(text);
					// set image
					Image image = ConnectionManager.getInstance().isConnected(session) ? connectedImage : disconnectedImage;
					menuItem.setImage(image);
					menuItem.addListener(SWT.Selection, menuItemListener);
				}

				if (!configuration.getSessions().isEmpty()) {
					new MenuItem(m, SWT.SEPARATOR);
				}

				MenuItem exit = new MenuItem(m, SWT.NONE);
				exit.setText("Exit");
				exit.addListener(SWT.Selection, new Listener() {
					public void handleEvent(Event event) {
						exit();
					}
				});

				m.setVisible(true);
			};
		});
	}

	private void exit() {
		disconnectAll();
		SessionConnectionMonitor.getInstance().stopMonitor();
		save();
		Tray tray = this.getDisplay().getSystemTray();
		for (TrayItem trayItem : tray.getItems()) {
			trayItem.dispose();
		}
		System.exit(0);
	}

	private void disposeImages() {
		applicationImage.dispose();
		connectImage.dispose();
		disconnectImage.dispose();
		connectAllImage.dispose();
		disconnectAllImage.dispose();
		connectedImage.dispose();
		disconnectedImage.dispose();
		appConnectedImage.dispose();
		appReconnectingImage.dispose();
	}

	/**
	 * @see org.eclipse.swt.widgets.Widget#dispose()
	 */
	public void dispose() {
		disposeImages();
		super.dispose();
	}

}
