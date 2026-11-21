## DELETE http://localhost:41771/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 21 Nov 2026 17:55:58 GMT
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
curl 'http://localhost:41771/echo' \
  -X DELETE
```
