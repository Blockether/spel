## DELETE http://localhost:46045/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 22 Aug 2026 11:38:32 GMT
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
curl 'http://localhost:46045/echo' \
  -X DELETE
```
