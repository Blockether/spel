## DELETE http://localhost:40863/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 26 Apr 2026 20:01:13 GMT
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
curl 'http://localhost:40863/echo' \
  -X DELETE
```
