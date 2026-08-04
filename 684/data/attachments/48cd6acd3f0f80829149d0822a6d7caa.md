## DELETE http://localhost:39269/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 04 Aug 2026 18:30:22 GMT
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
curl 'http://localhost:39269/echo' \
  -X DELETE
```
