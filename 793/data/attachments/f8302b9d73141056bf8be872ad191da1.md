## DELETE http://localhost:33417/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 20 Oct 2026 21:23:29 GMT
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
curl 'http://localhost:33417/echo' \
  -X DELETE
```
