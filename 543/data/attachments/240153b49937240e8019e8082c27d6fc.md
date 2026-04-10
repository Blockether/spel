## DELETE http://localhost:34461/echo → 200 OK

### Request Headers
```
DELETE http://localhost:34461/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 10 Apr 2026 03:58:33 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:34461/echo' \
  -X DELETE
```
