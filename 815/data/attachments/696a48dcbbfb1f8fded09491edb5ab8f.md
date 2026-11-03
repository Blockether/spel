## DELETE http://localhost:42673/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 03 Nov 2026 22:23:58 GMT
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
curl 'http://localhost:42673/echo' \
  -X DELETE
```
