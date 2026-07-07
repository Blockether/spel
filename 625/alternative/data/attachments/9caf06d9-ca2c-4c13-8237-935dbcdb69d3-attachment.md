## DELETE http://localhost:41093/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 07 Jul 2026 10:38:23 GMT
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
curl 'http://localhost:41093/echo' \
  -X DELETE
```
